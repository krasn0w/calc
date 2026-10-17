package io.github.krasn0w.calc

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * The calculator UI, built in code (no XML, no AndroidX).
 * The host (service) supplies callbacks for window-level actions.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility", "SetTextI18n")
class CalcPanel(
    context: Context,
    private val engine: Engine,
    initialAlphaPct: Int,
    initialPanelOpen: Boolean,
    private val host: Host,
) : LinearLayout(context) {

    interface Host {
        fun onDrag(dx: Int, dy: Int, phase: Int)   // phase: 0 = down, 1 = move, 2 = up
        fun onAlphaChanged(pct: Int)
        fun onPanelToggled(open: Boolean, extraHeightPx: Int)
        fun onClose()
        fun onMemoryChanged()
    }

    private enum class Kind(val bg: Int, val fg: Int, val ripple: Int, val sp: Float) {
        DIGIT(0xFFFFFFFF.toInt(), 0xFF111113.toInt(), 0x33000000, 24f),
        OP(0xFFFF9500.toInt(), 0xFFFFFFFF.toInt(), 0x55FFFFFF, 26f),
        SERVICE(0xFF4A4A50.toInt(), 0xFFFFFFFF.toInt(), 0x44FFFFFF, 20f),
        PANEL(0xFF4A4A50.toInt(), 0xFFFFFFFF.toInt(), 0x44FFFFFF, 17f),
    }

    private val key = dp(52)
    private val gap = dp(8)
    private val pad = dp(12)
    private val innerWidth = key * 4 + gap * 3

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private val line1 = displayLine()
    private val line2 = displayLine()
    private val memMark = TextView(context).apply {
        text = "M"
        setTextColor(Kind.OP.bg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = medium
        visibility = View.GONE
    }
    private lateinit var clearKey: TextView
    private lateinit var moreKey: TextView
    private val extraPanel = LinearLayout(context).apply { orientation = VERTICAL }
    private val extraHeight = key * 2 + gap * 2
    var panelOpen = initialPanelOpen
        private set

    init {
        orientation = VERTICAL
        setPadding(pad, dp(6), pad, pad)
        background = GradientDrawable().apply {
            setColor(0xFF161618.toInt())
            cornerRadius = dp(26).toFloat()
        }

        addView(topBar(initialAlphaPct), LayoutParams(innerWidth, dp(36)))
        addView(display(), LayoutParams(innerWidth, dp(88)).apply { bottomMargin = gap })

        // Main keypad: digits white, operators orange, service graphite.
        addRow(
            keyView("⌫", Kind.SERVICE, { engine.backspace() }, { engine.clear() }).also { clearKey = it },
            keyView("±", Kind.SERVICE, { engine.toggleSign() }),
            keyView("%", Kind.SERVICE, { engine.percent() }),
            keyView("÷", Kind.OP, { engine.op('/') }),
        )
        addRow(digit('7'), digit('8'), digit('9'), keyView("×", Kind.OP, { engine.op('*') }))
        addRow(digit('4'), digit('5'), digit('6'), keyView("−", Kind.OP, { engine.op('-') }))
        addRow(digit('1'), digit('2'), digit('3'), keyView("+", Kind.OP, { engine.op('+') }))
        addRow(
            keyView("⋯", Kind.SERVICE, { togglePanel() }).also { moreKey = it },
            digit('0'),
            keyView(",", Kind.DIGIT, { engine.decimal() }),
            keyView("=", Kind.OP, { engine.equals() }),
            last = true,
        )

        // Slide-out panel with the rarer functions.
        extraPanel.addView(row(
            keyView("MS", Kind.PANEL, { engine.memStore() }),
            keyView("MR", Kind.PANEL, { engine.memRecall() }, { engine.memClear() }),
            keyView("M+", Kind.PANEL, { engine.memAdd() }),
            keyView("M−", Kind.PANEL, { engine.memSub() }),
        ), LayoutParams(innerWidth, key).apply { topMargin = gap })
        extraPanel.addView(row(
            keyView("(", Kind.PANEL, { engine.lparen() }),
            keyView(")", Kind.PANEL, { engine.rparen() }),
            keyView("xʸ", Kind.PANEL, { engine.op('^') }),
            keyView("√", Kind.PANEL, { engine.sqrt() }),
        ), LayoutParams(innerWidth, key).apply { topMargin = gap })
        extraPanel.clipChildren = true
        addView(extraPanel, LayoutParams(innerWidth, if (panelOpen) extraHeight else 0))
        styleMoreKey()

        refresh()
    }

    // ---------- building blocks ----------

    private fun topBar(alphaPct: Int): View {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(context).apply {
            text = "◐"
            setTextColor(0xFF8E8E93.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        val seek = SeekBar(context).apply {
            min = MIN_ALPHA
            max = 100
            progress = alphaPct.coerceIn(MIN_ALPHA, 100)
            progressTintList = ColorStateList.valueOf(Kind.OP.bg)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF5A5A60.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) host.onAlphaChanged(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(0xFFB0B0B5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            setOnClickListener { host.onClose() }
            contentDescription = "Закрыть"
        }
        bar.addView(icon, LayoutParams(dp(24), LayoutParams.MATCH_PARENT))
        bar.addView(seek, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bar.addView(close, LayoutParams(dp(32), dp(32)))
        attachDrag(icon)
        return bar
    }

    private fun display(): View {
        val box = FrameLayout(context)
        val lines = LinearLayout(context).apply { orientation = VERTICAL }
        lines.addView(line1, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        lines.addView(line2, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(lines, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        box.addView(memMark, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { leftMargin = dp(4); topMargin = dp(2) })
        attachDrag(box)
        return box
    }

    private fun displayLine() = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START
        includeFontPadding = false
        fontFeatureSettings = "tnum"
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun digit(d: Char) = keyView(d.toString(), Kind.DIGIT, { engine.digit(d) })

    private fun keyView(label: String, kind: Kind, onTap: () -> Unit, onLong: (() -> Unit)? = null): TextView {
        val oval = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(kind.bg) }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(kind.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, kind.sp)
            typeface = medium
            includeFontPadding = false
            background = RippleDrawable(ColorStateList.valueOf(kind.ripple), oval, mask)
            isHapticFeedbackEnabled = true
            setOnClickListener {
                Log.d("Calc", "key $label")
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val memBefore = engine.memory
                onTap()
                if (engine.memory != memBefore) host.onMemoryChanged()
                refresh()
            }
            if (onLong != null) setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val memBefore = engine.memory
                onLong()
                if (engine.memory != memBefore) host.onMemoryChanged()
                refresh()
                true
            }
        }
    }

    private fun row(vararg keys: View): LinearLayout {
        val r = LinearLayout(context).apply { orientation = HORIZONTAL }
        keys.forEachIndexed { i, k ->
            r.addView(k, LayoutParams(key, key).apply { if (i > 0) leftMargin = gap })
        }
        return r
    }

    private fun addRow(vararg keys: View, last: Boolean = false) {
        addView(row(*keys), LayoutParams(innerWidth, key).apply { if (!last) bottomMargin = gap })
    }

    private fun attachDrag(v: View) {
        var sx = 0f
        var sy = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; host.onDrag(0, 0, 0) }
                MotionEvent.ACTION_MOVE -> host.onDrag((e.rawX - sx).toInt(), (e.rawY - sy).toInt(), 1)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    host.onDrag((e.rawX - sx).toInt(), (e.rawY - sy).toInt(), 2)
            }
            true
        }
    }

    // ---------- behaviour ----------

    private fun togglePanel() {
        panelOpen = !panelOpen
        styleMoreKey()
        host.onPanelToggled(panelOpen, extraHeight)
        val lp = extraPanel.layoutParams
        val from = lp.height
        val to = if (panelOpen) extraHeight else 0
        ValueAnimator.ofInt(from, to).apply {
            duration = 170
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.height = it.animatedValue as Int
                extraPanel.layoutParams = lp
            }
            start()
        }
    }

    private fun styleMoreKey() {
        val bg = (moreKey.background as RippleDrawable).getDrawable(0) as GradientDrawable
        bg.setColor(if (panelOpen) 0xFF7A7A82.toInt() else Kind.SERVICE.bg)
    }

    fun refresh() {
        val big = 34f
        val small = 17f
        if (engine.justEvaluated) {
            setLine(line1, engine.lastExpression + " =", small, GREY, light, big = false)
            setLine(line2, engine.expressionText(), big, Color.WHITE, light, big = true)
        } else {
            setLine(line1, engine.expressionText(), big, Color.WHITE, light, big = true)
            val err = engine.errorMessage
            when {
                err != null -> setLine(line2, err, small, Kind.OP.bg, medium, big = false)
                else -> setLine(line2, engine.preview()?.let { "= $it" } ?: "", small, GREY, light, big = false)
            }
        }
        clearKey.text = if (engine.justEvaluated || engine.tokens.isEmpty() || engine.errorMessage != null) "C" else "⌫"
        memMark.visibility = if (engine.memory != null) View.VISIBLE else View.GONE
    }

    private fun setLine(tv: TextView, text: String, sp: Float, color: Int, face: Typeface, big: Boolean) {
        tv.typeface = face
        tv.setTextColor(color)
        tv.text = text
        // Shrink long text to fit the fixed width, down to a floor; beyond that, ellipsize at the start.
        val avail = innerWidth - dp(8)
        val paint = Paint(tv.paint)
        var size = sp
        val floor = if (big) 20f else 13f
        while (size > floor) {
            paint.textSize = spPx(size)
            if (paint.measureText(text) <= avail) break
            size -= 1f
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun spPx(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun panelExtraHeight() = extraHeight

    companion object {
        const val MIN_ALPHA = 15
        private const val GREY = 0xFF8E8E93.toInt()
    }
}
