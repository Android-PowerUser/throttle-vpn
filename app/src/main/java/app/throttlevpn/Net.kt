package app.throttlevpn

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

object Net {
    const val FIN = 1
    const val SYN = 2
    const val RST = 4
    const val PSH = 8
    const val ACK = 16

    private val ipId = AtomicInteger(1)

    fun addr(ip: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte())
    )

    private fun sumWords(b: ByteArray, off: Int, len: Int): Long {
        var sum = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < end) sum += (b[i].toInt() and 0xff) shl 8
        return sum
    }

    private fun fold(s0: Long): Int {
        var s = s0
        while ((s shr 16) != 0L) s = (s and 0xffff) + (s shr 16)
        return s.inv().toInt() and 0xffff
    }

    private fun ipHeader(b: ByteArray, total: Int, proto: Int, src: Int, dst: Int) {
        val bb = ByteBuffer.wrap(b)
        b[0] = 0x45
        bb.putShort(2, total.toShort())
        bb.putShort(4, ipId.incrementAndGet().toShort())
        bb.putShort(6, 0x4000.toShort())
        b[8] = 64
        b[9] = proto.toByte()
        bb.putInt(12, src)
        bb.putInt(16, dst)
        bb.putShort(10, 0)
        bb.putShort(10, fold(sumWords(b, 0, 20)).toShort())
    }

    fun buildTcp(
        srcIp: Int, dstIp: Int, sp: Int, dp: Int, seq: Int, ack: Int, flags: Int, win: Int,
        data: ByteArray?, off: Int, len: Int, mss: Int = 0
    ): ByteArray {
        val optLen = if (mss > 0) 4 else 0
        val tcpLen = 20 + optLen + len
        val total = 20 + tcpLen
        val b = ByteArray(total)
        ipHeader(b, total, 6, srcIp, dstIp)
        val bb = ByteBuffer.wrap(b)
        bb.putShort(20, sp.toShort())
        bb.putShort(22, dp.toShort())
        bb.putInt(24, seq)
        bb.putInt(28, ack)
        b[32] = (((20 + optLen) / 4) shl 4).toByte()
        b[33] = flags.toByte()
        bb.putShort(34, win.toShort())
        if (mss > 0) {
            b[40] = 2
            b[41] = 4
            bb.putShort(42, mss.toShort())
        }
        if (len > 0 && data != null) System.arraycopy(data, off, b, 40 + optLen, len)
        var s = sumWords(b, 12, 8) + 6 + tcpLen + sumWords(b, 20, tcpLen)
        bb.putShort(36, fold(s).toShort())
        return b
    }

    fun buildUdp(
        srcIp: Int, dstIp: Int, sp: Int, dp: Int, data: ByteArray, off: Int, len: Int
    ): ByteArray {
        val udpLen = 8 + len
        val total = 20 + udpLen
        val b = ByteArray(total)
        ipHeader(b, total, 17, srcIp, dstIp)
        val bb = ByteBuffer.wrap(b)
        bb.putShort(20, sp.toShort())
        bb.putShort(22, dp.toShort())
        bb.putShort(24, udpLen.toShort())
        System.arraycopy(data, off, b, 28, len)
        val s = sumWords(b, 12, 8) + 17 + udpLen + sumWords(b, 20, udpLen)
        var c = fold(s)
        if (c == 0) c = 0xffff
        bb.putShort(26, c.toShort())
        return b
    }
}
