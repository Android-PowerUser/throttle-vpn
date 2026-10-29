package app.throttlevpn

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap

object LimitStore {
    /** Speed steps in bytes/second. Last entry (0) = unlimited. */
    val STEPS = longArrayOf(
        10_000, 20_000, 50_000, 100_000, 200_000, 500_000, 1_000_000,
        2_000_000, 5_000_000, 10_000_000, 20_000_000, 50_000_000, 0
    )
    const val UNLIMITED = 12

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
        global.upIdx = prefs.getInt("gup", UNLIMITED)
        global.downIdx = prefs.getInt("gdown", UNLIMITED)
        selected = prefs.getStringSet("sel", emptySet())!!.toSet()
        applyGlobal()
    }

    private fun applyGlobal() {
        global.up.rate = if (globalEnabled) STEPS[global.upIdx] else 0
        global.down.rate = if (globalEnabled) STEPS[global.downIdx] else 0
    }

    fun setGlobalEnabled(v: Boolean) {
        globalEnabled = v
        prefs.edit().putBoolean("gen", v).apply()
        applyGlobal()
    }

    fun setGlobalUp(i: Int) {
        global.upIdx = i
        prefs.edit().putInt("gup", i).apply()
        applyGlobal()
    }

    fun setGlobalDown(i: Int) {
        global.downIdx = i
        prefs.edit().putInt("gdown", i).apply()
        applyGlobal()
    }

    fun limits(pkg: String): Limits = apps.getOrPut(pkg) {
        Limits().also {
            it.upIdx = prefs.getInt("u_$pkg", UNLIMITED)
            it.downIdx = prefs.getInt("d_$pkg", UNLIMITED)
            it.up.rate = STEPS[it.upIdx]
            it.down.rate = STEPS[it.downIdx]
        }
    }

    fun setUp(pkg: String, i: Int) {
        val l = limits(pkg)
        l.upIdx = i
        l.up.rate = STEPS[i]
        prefs.edit().putInt("u_$pkg", i).apply()
    }

    fun setDown(pkg: String, i: Int) {
        val l = limits(pkg)
        l.downIdx = i
        l.down.rate = STEPS[i]
        prefs.edit().putInt("d_$pkg", i).apply()
    }

    fun select(pkg: String, on: Boolean) {
        selected = if (on) selected + pkg else selected - pkg
        prefs.edit().putStringSet("sel", selected.toSet()).apply()
    }

    fun label(idx: Int): String {
        val v = STEPS[idx]
        if (v == 0L) return "Unbegrenzt"
        return if (v >= 1_000_000) "${v / 1_000_000} MB/s" else "${v / 1000} KB/s"
    }
}
