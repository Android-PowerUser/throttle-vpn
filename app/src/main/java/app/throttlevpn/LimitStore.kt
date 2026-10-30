package app.throttlevpn

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.pow

object LimitStore {
    private const val MIN_RATE = 10_000.0      // 10 KB/s
    private const val DECADES = 3.7            // up to ~50 MB/s
    private const val MAX_POS = 0.98f          // slider beyond this = unlimited

    class Limits {
        val up = RateLimiter()
        val down = RateLimiter()
        var upRate = 0L     // bytes/s, 0 = unlimited
        var downRate = 0L
    }

    private lateinit var prefs: SharedPreferences
    private val apps = ConcurrentHashMap<String, Limits>()
    val global = Limits()

    var globalEnabled by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var wanted: Boolean
        get() = prefs.getBoolean("wanted", false)
        set(v) { prefs.edit().putBoolean("wanted", v).apply() }

    fun rateFromPos(p: Float): Long =
        if (p >= MAX_POS) 0L else (MIN_RATE * 10.0.pow(p / MAX_POS * DECADES)).toLong()

    fun posFromRate(r: Long): Float =
        if (r <= 0L) 1f else (log10(r / MIN_RATE) / DECADES * MAX_POS).toFloat().coerceIn(0f, MAX_POS)

    fun label(r: Long): String = when {
        r <= 0L -> "Unbegrenzt"
        r >= 1_000_000 -> String.format("%.1f MB/s", r / 1_000_000.0)
        else -> "${r / 1000} KB/s"
    }

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("limits2", Context.MODE_PRIVATE)
        globalEnabled = prefs.getBoolean("gen", false)
        global.upRate = prefs.getLong("gup", 0L)
        global.downRate = prefs.getLong("gdown", 0L)
        selected = prefs.getStringSet("sel", emptySet())!!.toSet()
        applyGlobal()
    }

    private fun applyGlobal() {
        global.up.rate = if (globalEnabled) global.upRate else 0
        global.down.rate = if (globalEnabled) global.downRate else 0
    }

    fun enableGlobal(v: Boolean) {
        globalEnabled = v
        prefs.edit().putBoolean("gen", v).apply()
        applyGlobal()
    }

    fun setGlobalUp(r: Long) {
        global.upRate = r
        prefs.edit().putLong("gup", r).apply()
        applyGlobal()
    }

    fun setGlobalDown(r: Long) {
        global.downRate = r
        prefs.edit().putLong("gdown", r).apply()
        applyGlobal()
    }

    fun limits(pkg: String): Limits = apps.getOrPut(pkg) {
        Limits().also {
            it.upRate = prefs.getLong("u_$pkg", 0L)
            it.downRate = prefs.getLong("d_$pkg", 0L)
            it.up.rate = it.upRate
            it.down.rate = it.downRate
        }
    }

    fun setUp(pkg: String, r: Long) {
        val l = limits(pkg)
        l.upRate = r
        l.up.rate = r
        prefs.edit().putLong("u_$pkg", r).apply()
    }

    fun setDown(pkg: String, r: Long) {
        val l = limits(pkg)
        l.downRate = r
        l.down.rate = r
        prefs.edit().putLong("d_$pkg", r).apply()
    }

    fun select(pkg: String, on: Boolean) {
        selected = if (on) selected + pkg else selected - pkg
        prefs.edit().putStringSet("sel", selected.toSet()).apply()
    }
}
