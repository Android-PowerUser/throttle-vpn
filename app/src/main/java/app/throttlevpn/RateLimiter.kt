package app.throttlevpn

/** Simple pacing limiter. rate = bytes/second, 0 = unlimited. Rate can change live. */
class RateLimiter {
    @Volatile var rate: Long = 0L
    private var next = 0L
    private val lock = Any()
    private val burstNs = 100_000_000L

    /** Blocks until n bytes may pass. */
    fun acquire(n: Int) {
        val r = rate
        if (r <= 0) return
        var wait: Long
        synchronized(lock) {
            val now = System.nanoTime()
            if (next < now - burstNs) next = now - burstNs
            if (next > now + 5_000_000_000L) next = now + 5_000_000_000L
            wait = next - now
            next += n * 1_000_000_000L / r
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
            } catch (_: InterruptedException) {
            }
        }
    }

    /** Non-blocking: returns false (=> drop packet) if too much is queued. */
    fun tryAcquire(n: Int): Boolean {
        val r = rate
        if (r <= 0) return true
        synchronized(lock) {
            val now = System.nanoTime()
            if (next < now - burstNs) next = now - burstNs
            if (next - now > 300_000_000L) return false
            next += n * 1_000_000_000L / r
            return true
        }
    }
}
