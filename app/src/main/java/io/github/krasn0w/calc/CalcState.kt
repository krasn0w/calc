package io.github.krasn0w.calc

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import java.math.BigDecimal

/** Process-wide state shared by the full-screen activity and the floating window. */
object CalcState {
    val engine = Engine()
    var panelOpen = false
    var alphaPct = 100
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("calc", Context.MODE_PRIVATE)
        prefs = p
        panelOpen = p.getBoolean(KEY_PANEL, false)
        alphaPct = p.getInt(KEY_ALPHA, 100)
        p.getString(KEY_MEMORY, null)?.let { s -> runCatching { engine.memory = BigDecimal(s) } }
    }

    fun save() {
        val p = prefs ?: return
        val m = engine.memory
        p.edit()
            .putBoolean(KEY_PANEL, panelOpen)
            .putInt(KEY_ALPHA, alphaPct)
            .apply { if (m == null) remove(KEY_MEMORY) else putString(KEY_MEMORY, m.toPlainString()) }
            .apply()
    }

    fun getInt(key: String, def: Int) = prefs?.getInt(key, def) ?: def
    fun putInts(vararg kv: Pair<String, Int>) {
        val e = prefs?.edit() ?: return
        kv.forEach { (k, v) -> e.putInt(k, v) }
        e.apply()
    }

    private const val KEY_PANEL = "panelOpen"
    private const val KEY_ALPHA = "alpha"
    private const val KEY_MEMORY = "memory"
}

object Fonts {
    val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
}

object Palette {
    const val BG = 0xFF161618.toInt()
    const val DIGIT = 0xFFFFFFFF.toInt()
    const val DIGIT_TEXT = 0xFF111113.toInt()
    const val OP = 0xFFFF9500.toInt()
    const val SERVICE = 0xFF4A4A50.toInt()
    const val SERVICE_ACTIVE = 0xFF7A7A82.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val MUTED = 0xFF8E8E93.toInt()
    const val ICON = 0xFFA4A4AA.toInt()
}
