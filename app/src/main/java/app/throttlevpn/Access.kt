package app.throttlevpn

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 5 minute free trial (wall clock, starts when the user confirms the intro dialog), then one-time purchase. */
object Access {
    const val TRIAL_MS = 5 * 60_000L

    private lateinit var prefs: SharedPreferences

    var premium by mutableStateOf(false)
        private set
    var trialStart by mutableStateOf(0L)
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("access", Context.MODE_PRIVATE)
        premium = prefs.getBoolean("premium", false)
        trialStart = prefs.getLong("trial_start", 0L)
    }

    fun startTrial() {
        if (trialStart == 0L) {
            trialStart = System.currentTimeMillis()
            prefs.edit().putLong("trial_start", trialStart).apply()
        }
    }

    fun unlock(v: Boolean) {
        premium = v
        prefs.edit().putBoolean("premium", v).apply()
    }

    /** Remaining trial time in ms (full trial if not started, 0 if over or clock was set back). */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long {
        if (trialStart == 0L) return TRIAL_MS
        val elapsed = now - trialStart
        if (elapsed < 0) return 0
        return (TRIAL_MS - elapsed).coerceAtLeast(0)
    }

    fun trialRunning(now: Long = System.currentTimeMillis()) = trialStart != 0L && remainingMs(now) > 0

    fun allowed(now: Long = System.currentTimeMillis()) = premium || trialRunning(now)
}
