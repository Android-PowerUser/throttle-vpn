package app.throttlevpn

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap

object LimitStore {
    /** Slider position 0..1000, logarithmic 10 KB/s .. 50 MB/s, 1000 = unlimited. */
    const val UNLIMITED = 1000

    fun rateFor(p: Int): Long =
        if (p >= UNLIMITED) 0L else (10_000.0 * Math.pow(10.0, 3.7 * p / 999.0)).toLong()

    class Limits {
        val up = RateLimiter()
        val down = RateLimiter()
        var upIdx = UNLIMITED
        var downIdx = UNLIMITED
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

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("limits", Context.MODE_PRIVATE)
        globalEnabled = prefs.getBoolean("gen", false)
        global.upIdx = prefs.getInt("gup2", UNLIMITED)
        global.downIdx = prefs.getInt("gdown2", UNLIMITED)
        selected = prefs.getStringSet("sel", emptySet())!!.toSet()
        applyGlobal()
    }

    private fun applyGlobal() {
        global.up.rate = if (globalEnabled) rateFor(global.upIdx) else 0
        global.down.rate = if (globalEnabled) rateFor(global.downIdx) else 0
    }

    fun enableGlobal(v: Boolean) {
        globalEnabled = v
        prefs.edit().putBoolean("gen", v).apply()
        applyGlobal()
    }

    fun setGlobalUp(i: Int) {
        global.upIdx = i
        prefs.edit().putInt("gup2", i).apply()
        applyGlobal()
    }

    fun setGlobalDown(i: Int) {
        global.downIdx = i
        prefs.edit().putInt("gdown2", i).apply()
        applyGlobal()
    }

    fun limits(pkg: String): Limits = apps.getOrPut(pkg) {
        Limits().also {
            it.upIdx = prefs.getInt("u2_$pkg", UNLIMITED)
            it.downIdx = prefs.getInt("d2_$pkg", UNLIMITED)
            it.up.rate = rateFor(it.upIdx)
            it.down.rate = rateFor(it.downIdx)
        }
    }

    fun setUp(pkg: String, i: Int) {
        val l = limits(pkg)
        l.upIdx = i
        l.up.rate = rateFor(i)
        prefs.edit().putInt("u2_$pkg", i).apply()
    }

    fun setDown(pkg: String, i: Int) {
        val l = limits(pkg)
        l.downIdx = i
        l.down.rate = rateFor(i)
        prefs.edit().putInt("d2_$pkg", i).apply()
    }

    fun select(pkg: String, on: Boolean) {
        selected = if (on) selected + pkg else selected - pkg
        prefs.edit().putStringSet("sel", selected.toSet()).apply()
    }

    fun label(idx: Int): String {
        val v = rateFor(idx)
        if (v == 0L) return "Unbegrenzt"
        return if (v >= 1_000_000) String.format("%.1f MB/s", v / 1_000_000.0) else "${v / 1000} KB/s"
    }
}
