package app.throttlevpn

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ThrottleVpnService : VpnService() {
    companion object {
        const val ACTION_START = "app.throttlevpn.START"
        const val ACTION_STOP = "app.throttlevpn.STOP"
        var running by mutableStateOf(false)
            private set
    }

    @Volatile var pool: ExecutorService = Executors.newCachedThreadPool()
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var out: FileOutputStream? = null
    private val writeLock = Any()
    @Volatile private var session: Any? = null
    private val tcpMap = ConcurrentHashMap<String, TcpConn>()
    private val udpMap = ConcurrentHashMap<String, UdpFlow>()
    @Volatile private var uidMap: Map<Int, String> = emptyMap()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LimitStore.init(this)
        when (intent?.action) {
            ACTION_STOP -> {
                LimitStore.wanted = false
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            null -> if (!LimitStore.wanted) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        LimitStore.wanted = false
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    fun write(p: ByteArray) {
        try {
            synchronized(writeLock) { out?.write(p) }
        } catch (_: Exception) {
        }
    }

    private fun startVpn() {
        stopVpn()
        LimitStore.wanted = true
        val b = Builder()
            .setSession("Throttle VPN")
            .setMtu(1500)
            .setBlocking(true)
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:8::2", 128)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        val map = HashMap<Int, String>()
        for (pkg in LimitStore.selected) {
            try {
                map[packageManager.getPackageUid(pkg, 0)] = pkg
            } catch (_: Exception) {
            }
        }
        try {
            if (LimitStore.globalEnabled) {
                b.addDisallowedApplication(packageName)
            } else {
                var n = 0
                for (pkg in LimitStore.selected) {
                    try {
                        b.addAllowedApplication(pkg)
                        n++
                    } catch (_: Exception) {
                    }
                }
                if (n == 0) b.addAllowedApplication(packageName)
            }
        } catch (_: Exception) {
        }
        uidMap = map

        val fd = b.establish()
        if (fd == null) {
            stopSelf()
            return
        }
        pool = Executors.newCachedThreadPool()
        tun = fd
        out = FileOutputStream(fd.fileDescriptor)
        val s = Any()
        session = s
        running = true

        Thread {
            val inp = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(32768)
            try {
                while (session === s) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    if (n > 0) handle(buf, n)
                }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true }.start()

        pool.execute {
            try {
                while (session === s) {
                    Thread.sleep(30_000)
                    sweep()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun stopVpn() {
        session = null
        running = false
        for (c in tcpMap.values) c.close()
        for (u in udpMap.values) u.close()
        tcpMap.clear()
        udpMap.clear()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        out = null
        pool.shutdownNow()
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        for (c in tcpMap.values) if (c.closed || now - c.lastActive > 10 * 60_000L) c.abort()
        for (u in udpMap.values) if (u.closed || now - u.lastActive > 90_000L) u.close()
    }

    private fun ownerUid(proto: Int, src: Int, sp: Int, dst: Int, dp: Int): Int = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.getConnectionOwnerUid(
            proto, InetSocketAddress(Net.addr(src), sp), InetSocketAddress(Net.addr(dst), dp)
        )
    } catch (_: Exception) {
        -1
    }

    private fun limitersFor(uid: Int): Pair<List<RateLimiter>, List<RateLimiter>> {
        val pkg = uidMap[uid]
        val l = if (pkg != null) LimitStore.limits(pkg) else null
        val up = listOfNotNull(l?.up, LimitStore.global.up)
        val down = listOfNotNull(l?.down, LimitStore.global.down)
        return Pair(up, down)
    }

    private fun handle(b: ByteArray, len: Int) {
        if (len < 20) return
        if (((b[0].toInt() shr 4) and 0xf) != 4) return // IPv6 dropped -> apps fall back to IPv4
        val ihl = (b[0].toInt() and 0xf) * 4
        val bb = ByteBuffer.wrap(b)
        val total = minOf(bb.getShort(2).toInt() and 0xffff, len)
        if (total < ihl + 8) return
        if ((bb.getShort(6).toInt() and 0x3fff) != 0) return // fragments not supported
        val proto = b[9].toInt() and 0xff
        val src = bb.getInt(12)
        val dst = bb.getInt(16)
        when (proto) {
            6 -> handleTcp(b, bb, ihl, total, src, dst)
            17 -> handleUdp(b, bb, ihl, total, src, dst)
        }
    }

    private fun handleTcp(b: ByteArray, bb: ByteBuffer, ihl: Int, total: Int, src: Int, dst: Int) {
        if (total < ihl + 20) return
        val sp = bb.getShort(ihl).toInt() and 0xffff
        val dp = bb.getShort(ihl + 2).toInt() and 0xffff
        val seq = bb.getInt(ihl + 4)
        val ack = bb.getInt(ihl + 8)
        val doff = ((b[ihl + 12].toInt() shr 4) and 0xf) * 4
        val flags = b[ihl + 13].toInt() and 0x3f
        val win = bb.getShort(ihl + 14).toInt() and 0xffff
        if (doff < 20 || ihl + doff > total) return
        val off = ihl + doff
        val len = total - off
        val key = "$src:$sp:$dst:$dp"
        val c = tcpMap[key]
        if (c != null) {
            c.onPacket(seq, ack, flags, win, b, off, len)
            return
        }
        if (flags and Net.SYN != 0 && flags and Net.ACK == 0) {
            var mss = 0
            var i = ihl + 20
            val end = ihl + doff
            while (i < end) {
                val kind = b[i].toInt() and 0xff
                if (kind == 0) break
                if (kind == 1) { i++; continue }
                if (i + 1 >= end) break
                val l = b[i + 1].toInt() and 0xff
                if (l < 2) break
                if (kind == 2 && l == 4 && i + 3 < end) mss = bb.getShort(i + 2).toInt() and 0xffff
                i += l
            }
            val uid = ownerUid(6, src, sp, dst, dp)
            val (up, down) = limitersFor(uid)
            val conn = TcpConn(this, src, sp, dst, dp, seq, mss, win, up, down) { tcpMap.remove(key, it) }
            tcpMap[key] = conn
            conn.start()
        } else if (flags and Net.RST == 0 && flags and Net.ACK != 0) {
            write(Net.buildTcp(dst, src, dp, sp, ack, 0, Net.RST, 0, null, 0, 0))
        }
    }

    private fun handleUdp(b: ByteArray, bb: ByteBuffer, ihl: Int, total: Int, src: Int, dst: Int) {
        val sp = bb.getShort(ihl).toInt() and 0xffff
        val dp = bb.getShort(ihl + 2).toInt() and 0xffff
        val off = ihl + 8
        val len = total - off
        if (len < 0) return
        val key = "$src:$sp:$dst:$dp"
        var f = udpMap[key]
        if (f == null) {
            val uid = ownerUid(17, src, sp, dst, dp)
            val (up, down) = limitersFor(uid)
            val nf = UdpFlow(this, src, sp, dst, dp, up, down) { udpMap.remove(key, it) }
            if (nf.sock == null) return
            udpMap[key] = nf
            nf.start()
            f = nf
        }
        f.send(b, off, len)
    }
}
