package app.throttlevpn

import java.util.concurrent.locks.LockSupport

/** Pacing limiter. rate = bytes/second, 0 = unlimited. Rate can change live. */
class RateLimiter {
    @Volatile var rate: Long = 0L
    private var next = 0L
    private val lock = Any()
    private val burstNs = 10_000_000L

    /** Reserves n bytes and returns how many ns the caller must wait before sending. */
    fun reserve(n: Int): Long {
        val r = rate
        if (r <= 0) return 0L
        synchronized(lock) {
            val now = System.nanoTime()
            if (next < now - burstNs) next = now - burstNs
            if (next > now + 5_000_000_000L) next = now + 5_000_000_000L
            val wait = next - now
            next += n * 1_000_000_000L / r
            return wait
        }
    }

    /** Non-blocking: returns false (=> drop packet) if too much is queued. */
    fun tryAcquire(n: Int): Boolean {
        val r = rate
        if (r <= 0) return true
        synchronized(lock) {
            val now = System.nanoTime()
            if (next < now - burstNs) next = now - burstNs
            if (next - now > 100_000_000L) return false
            next += n * 1_000_000_000L / r
            return true
        }
    }

    companion object {
        /** Reserves in all limiters, then waits only once for the longest delay. */
        fun acquireAll(list: List<RateLimiter>, n: Int) {
            var w = 0L
            for (l in list) {
                val x = l.reserve(n)
                if (x > w) w = x
            }
            if (w < 1_000_000L) return // debt stays booked, paid back by later waits
            val end = System.nanoTime() + w
            while (!Thread.currentThread().isInterrupted) {
                val rem = end - System.nanoTime()
                if (rem <= 0) break
                LockSupport.parkNanos(rem)
            }
        }
    }
}
