package app.throttlevpn

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One TCP connection of an app, terminated locally and forwarded through a protected socket.
 * Upload is throttled by delaying writes to the server (window shrinks -> app slows down),
 * download by pacing the writes back into the tunnel.
 */
class TcpConn(
    private val vpn: ThrottleVpnService,
    private val clientIp: Int, private val clientPort: Int,
    private val serverIp: Int, private val serverPort: Int,
    private val clientIsn: Int, clientMss: Int, clientWin: Int,
    private val up: List<RateLimiter>, private val down: List<RateLimiter>,
    private val onClosed: (TcpConn) -> Unit,
) {
    @Volatile var closed = false
    @Volatile var lastActive = System.currentTimeMillis()

    private val mss = minOf(if (clientMss > 0) clientMss else 536, 1400)
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val ourIsn = kotlin.random.Random.nextInt()

    @Volatile private var sndUna = ourIsn
    @Volatile private var sndNxt = ourIsn
    @Volatile private var clientWindow = clientWin
    @Volatile private var clientNext = clientIsn + 1
    @Volatile private var clientFin = false
    @Volatile private var finSent = false
    @Volatile private var ch: SocketChannel? = null

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val queued = AtomicInteger()
    private var workerStarted = false
    private val fin = ByteArray(0)

    fun start() {
        vpn.pool.execute { run() }
    }

    private fun recvWindow(): Int = (65535 - queued.get()).coerceIn(0, 65535)

    private fun send(seq: Int, flags: Int, data: ByteArray?, off: Int, len: Int, mss: Int = 0) {
        vpn.write(
            Net.buildTcp(
                serverIp, clientIp, serverPort, clientPort, seq, clientNext, flags,
                recvWindow(), data, off, len, mss
            )
        )
    }

    private fun sendAck() = send(sndNxt, Net.ACK, null, 0, 0)

    private fun sendRst() = send(sndNxt, Net.RST or Net.ACK, null, 0, 0)

    private fun connect(): SocketChannel? {
        return try {
            val c = SocketChannel.open()
            ch = c
            if (!vpn.protect(c.socket())) throw IOException("protect failed")
            c.socket().connect(InetSocketAddress(Net.addr(serverIp), serverPort), 10000)
            c.socket().tcpNoDelay = true
            c
        } catch (e: Exception) {
            null
        }
    }

    private fun run() {
        val c = connect()
        if (c == null || closed) {
            if (!closed) {
                vpn.write(
                    Net.buildTcp(
                        serverIp, clientIp, serverPort, clientPort, 0, clientIsn + 1,
                        Net.RST or Net.ACK, 0, null, 0, 0
                    )
                )
            }
            close()
            return
        }
        sndNxt = ourIsn + 1
        vpn.write(
            Net.buildTcp(
                serverIp, clientIp, serverPort, clientPort, ourIsn, clientIsn + 1,
                Net.SYN or Net.ACK, 65535, null, 0, 0, mss
            )
        )
        val buf = ByteArray(16384)
        try {
            while (!closed) {
                val bb = ByteBuffer.wrap(buf, 0, chunkSize())
                val n = c.read(bb)
                if (n < 0) break
                if (n == 0) continue
                lastActive = System.currentTimeMillis()
                sendData(buf, n)
            }
        } catch (e: Exception) {
            if (!closed) {
                sendRst()
                close()
            }
            return
        }
        if (!closed) sendFin()
    }

    private fun chunkSize(): Int {
        var r = 0L
        for (l in down) {
            val x = l.rate
            if (x > 0 && (r == 0L || x < r)) r = x
        }
        return if (r == 0L) 16384 else (r / 20).coerceIn(1400L, 16384L).toInt()
    }

    private fun sendData(buf: ByteArray, len: Int) {
        var o = 0
        var rem = len
        while (rem > 0 && !closed) {
            var seg = 0
            var seq = 0
            RateLimiter.acquireAll(down, minOf(rem, mss))
            lock.withLock {
                while (!closed && clientWindow - (sndNxt - sndUna) <= 0) {
                    cond.await(500, TimeUnit.MILLISECONDS)
                }
                if (closed) return
                seg = minOf(rem, mss, clientWindow - (sndNxt - sndUna))
                seq = sndNxt
                sndNxt = seq + seg
            }
            val flags = Net.ACK or (if (rem == seg) Net.PSH else 0)
            send(seq, flags, buf, o, seg)
            o += seg
            rem -= seg
        }
    }

    private fun sendFin() {
        var seq = 0
        lock.withLock {
            seq = sndNxt
            sndNxt = seq + 1
            finSent = true
        }
        send(seq, Net.FIN or Net.ACK, null, 0, 0)
        checkDone()
    }

    private fun checkDone() {
        if (clientFin && finSent && sndUna == sndNxt) close()
    }

    private fun handleAck(ack: Int, win: Int) {
        lock.withLock {
            if (ack - sndUna > 0 && ack - sndNxt <= 0) sndUna = ack
            clientWindow = win
            cond.signalAll()
        }
        checkDone()
    }

    /** Called from the tunnel reader thread. Must never block. */
    fun onPacket(seq: Int, ack: Int, flags: Int, win: Int, data: ByteArray, off: Int, len: Int) {
        lastActive = System.currentTimeMillis()
        if (flags and Net.RST != 0) {
            close()
            return
        }
        if (flags and Net.SYN != 0) return
        if (flags and Net.ACK != 0) handleAck(ack, win)
        if (len > 0) {
            if (seq == clientNext) {
                val d = data.copyOfRange(off, off + len)
                clientNext = seq + len
                queued.addAndGet(len)
                queue.add(d)
                startWorker()
                sendAck()
            } else {
                sendAck()
                return
            }
        }
        if (flags and Net.FIN != 0 && !clientFin && seq + len == clientNext) {
            clientFin = true
            clientNext += 1
            queue.add(fin)
            startWorker()
            sendAck()
            checkDone()
        }
    }

    private fun startWorker() {
        if (workerStarted) return
        workerStarted = true
        vpn.pool.execute { uploadWorker() }
    }

    private fun uploadWorker() {
        try {
            while (!closed) {
                val d = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (closed) return
                val c = ch ?: return
                if (d.isEmpty()) {
                    try { c.shutdownOutput() } catch (_: Exception) {}
                    return
                }
                var o = 0
                while (o < d.size && !closed) {
                    val sz = minOf(1400, d.size - o)
                    RateLimiter.acquireAll(up, sz)
                    val bb = ByteBuffer.wrap(d, o, sz)
                    while (bb.hasRemaining()) c.write(bb)
                    o += sz
                }
                lastActive = System.currentTimeMillis()
                val q = queued.addAndGet(-d.size)
                if (65535 - (q + d.size) < 16384) sendAck()
            }
        } catch (e: Exception) {
            if (!closed) {
                sendRst()
                close()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        lock.withLock { cond.signalAll() }
        queue.offer(fin)
        try { ch?.close() } catch (_: Exception) {}
        onClosed(this)
    }

    fun abort() {
        if (!closed) {
            sendRst()
            close()
        }
    }
}
