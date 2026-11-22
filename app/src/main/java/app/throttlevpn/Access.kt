package app.throttlevpn

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Free trial = 300 seconds of use, counted with a monotonic clock (SystemClock.elapsedRealtime),
 * so changing the system time has no effect. Counts while the app is in the foreground or the VPN runs.
 */
object Access {
    const val TRIAL_MS = 300_000L

    private lateinit var prefs: SharedPreferences

    var premium by mutableStateOf(false)
        private set
    var trialStarted by mutableStateOf(false)
        private set
    var exhausted by mutableStateOf(false)
        private set

    @Volatile var foreground = false
    @Volatile var expiryListener: (() -> Unit)? = null
    @Volatile private var usedMs = 0L
    @Volatile private var last = SystemClock.elapsedRealtime()

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("access", Context.MODE_PRIVATE)
        premium = prefs.getBoolean("premium", false)
        trialStarted = prefs.getBoolean("trial_started", false)
        usedMs = prefs.getLong("used_ms", 0L)
        exhausted = trialStarted && usedMs >= TRIAL_MS
        last = SystemClock.elapsedRealtime()
        Thread {
            while (true) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                tick()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val d = (now - last).coerceIn(0L, 5000L)
        last = now
        if (premium || !trialStarted || exhausted) return
        if (!foreground && !ThrottleVpnService.running) return
        usedMs += d
        prefs.edit().putLong("used_ms", usedMs).apply()
        if (usedMs >= TRIAL_MS) {
            exhausted = true
            expiryListener?.invoke()
        }
    }

    fun startTrial() {
        if (!trialStarted) {
            last = SystemClock.elapsedRealtime()
            trialStarted = true
            prefs.edit().putBoolean("trial_started", true).apply()
        }
    }

    fun unlock(v: Boolean) {
        premium = v
        prefs.edit().putBoolean("premium", v).apply()
    }

    fun allowed() = premium || (trialStarted && !exhausted)
}
