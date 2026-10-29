package app.throttlevpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** UDP flow of an app. Limits drop packets over the budget (acts as congestion signal). */
class UdpFlow(
    private val vpn: ThrottleVpnService,
    private val clientIp: Int, private val clientPort: Int,
    private val serverIp: Int, private val serverPort: Int,
    private val up: List<RateLimiter>, private val down: List<RateLimiter>,
    private val onClosed: (UdpFlow) -> Unit,
) {
    @Volatile var closed = false
    @Volatile var lastActive = System.currentTimeMillis()
    val sock: DatagramSocket? = try {
        val s = DatagramSocket()
        if (!vpn.protect(s)) {
            s.close()
            null
        } else {
            s.connect(InetSocketAddress(Net.addr(serverIp), serverPort))
            s
        }
    } catch (e: Exception) {
        null
    }

    fun start() {
        val s = sock ?: return
        vpn.pool.execute {
            val buf = ByteArray(65535)
            try {
                while (!closed) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    lastActive = System.currentTimeMillis()
                    var ok = true
                    for (l in down) if (!l.tryAcquire(p.length)) ok = false
                    if (!ok) continue
                    vpn.write(Net.buildUdp(serverIp, clientIp, serverPort, clientPort, buf, 0, p.length))
                }
            } catch (_: Exception) {
            }
            close()
        }
    }

    fun send(data: ByteArray, off: Int, len: Int) {
        val s = sock ?: return
        lastActive = System.currentTimeMillis()
        for (l in up) if (!l.tryAcquire(len)) return
        try {
            s.send(DatagramPacket(data, off, len))
        } catch (e: Exception) {
            close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try { sock?.close() } catch (_: Exception) {}
        onClosed(this)
    }
}
