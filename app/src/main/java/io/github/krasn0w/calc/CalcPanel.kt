package io.github.krasn0w.calc

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The calculator UI as one hand-laid-out ViewGroup.
 *
 * Geometry is derived from the available size only: a 4-column grid of square cells
 * anchored to the bottom, keys as exact circles centred in their cells, the display
 * taking whatever height is left. Any window size therefore lays out the same way,
 * just scaled. [floating] switches between the full-screen app and the overlay window.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CalcPanel(context: Context, val floating: Boolean, private val host: Host) : ViewGroup(context) {

    interface Host {
        fun onModeToggle()
        fun onClose()
        fun onDrag(dx: Int, dy: Int, phase: Int) {}      // phase: 0 down, 1 move, 2 up
        fun onResize(dx: Int, dy: Int, phase: Int) {}
        fun onAlphaChanged(pct: Int) {}
        fun onPanelToggled(open: Boolean, deltaPx: Int) {}
    }

    private val engine = CalcState.engine
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density + 0.5f).toInt()

    private val padSide = dp(if (floating) 10f else 16f)
    private val padTop = dp(if (floating) 2f else 4f)
    private val padBottom = dp(if (floating) 12f else 16f)
    private var barH = dp(if (floating) 36f else 52f)
    private val displayMin = dp(if (floating) 56f else 96f)
    val windowRadius = dp(26f).toFloat()

    // ---------- children ----------

    private val rects = HashMap<View, Rect>()
    private val mainKeys = ArrayList<Triple<KeyView, Int, Int>>()   // view, row, col
    private val panelKeys = ArrayList<Triple<KeyView, Int, Int>>()
    private val line1 = displayLine()
    private val line2 = displayLine()
    private val memMark = TextView(context).apply {
        text = "M"
        setTextColor(Palette.OP)
        typeface = Fonts.light
        includeFontPadding = false
        visibility = View.GONE
    }
    private val modeBtn = IconButton(context, "mode", if (floating) IconButton.Glyph.TO_FULL else IconButton.Glyph.TO_WINDOW)
    private val closeBtn = IconButton(context, "close", IconButton.Glyph.CLOSE)
    private val opacityIcon = if (floating) IconButton(context, "opacity", IconButton.Glyph.OPACITY, clickable = false) else null
    private val seek: SeekBar? = if (floating) SeekBar(context).apply {
        min = MIN_ALPHA
        max = 100
        progress = CalcState.alphaPct.coerceIn(MIN_ALPHA, 100)
        progressTintList = ColorStateList.valueOf(Palette.OP)
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        progressBackgroundTintList = ColorStateList.valueOf(0xFF5A5A60.toInt())
        contentDescription = "seek"
        setPadding(dp(10f), 0, dp(10f), 0)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) host.onAlphaChanged(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    } else null
    private val corner = if (floating) CornerView(context, windowRadius) else null

    private lateinit var clearKey: KeyView
    private lateinit var moreKey: KeyView

    var panelOpen = CalcState.panelOpen
        private set
    private var fraction = if (panelOpen) 1f else 0f
    private var animator: ValueAnimator? = null

    /** Current cell size (key diameter + gap), in px. */
    var cell = 0
        private set

    init {
        background = if (floating) GradientDrawable().apply {
            setColor(Palette.BG)
            cornerRadius = windowRadius
        } else GradientDrawable().apply { setColor(Palette.BG) }
        clipChildren = true

        // Panel keys first, so they slide out from behind the keypad.
        val panelDef = listOf(
            listOf("MS", "MR", "M+", "M−"),
            listOf("(", ")", "xʸ", "√"),
        )
        panelDef.forEachIndexed { r, row ->
            row.forEachIndexed { c, label -> panelKeys += Triple(key(label, label, KeyView.Kind.PANEL), r, c) }
        }

        val k = KeyView.Kind.DIGIT
        val o = KeyView.Kind.OP
        val s = KeyView.Kind.SERVICE
        val mainDef = listOf(
            listOf("clear" to s, "±" to s, "%" to s, "÷" to o),
            listOf("7" to k, "8" to k, "9" to k, "×" to o),
            listOf("4" to k, "5" to k, "6" to k, "−" to o),
            listOf("1" to k, "2" to k, "3" to k, "+" to o),
            listOf("more" to s, "0" to k, "," to k, "=" to o),
        )
        mainDef.forEachIndexed { r, row ->
            row.forEachIndexed { c, (id, kind) -> mainKeys += Triple(key(id, id, kind), r, c) }
        }
        clearKey = mainKeys.first { it.first.keyId == "clear" }.first
        moreKey = mainKeys.first { it.first.keyId == "more" }.first
        moreKey.icon = KeyView.Icon.MORE
        moreKey.fillColor = if (panelOpen) Palette.SERVICE_ACTIVE else Palette.SERVICE

        addView(line1)
        addView(line2)
        addView(memMark)
        opacityIcon?.let { addView(it) }
        seek?.let { addView(it) }
        addView(modeBtn)
        addView(closeBtn)
        corner?.let { addView(it) }

        modeBtn.setOnClickListener { log("key mode"); host.onModeToggle() }
        closeBtn.setOnClickListener { log("key close"); host.onClose() }

        refresh()
    }

    private fun key(id: String, label: String, kind: KeyView.Kind): KeyView {
        val v = KeyView(context, id, label, kind)
        v.setOnClickListener {
            log("key $id")
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            press(id)
        }
        if (id == "clear" || id == "MR") v.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (id == "clear") engine.clear() else engine.memClear()
            CalcState.save()
            refresh()
            true
        }
        addView(v)
        return v
    }

    private fun press(id: String) {
        val memBefore = engine.memory
        when (id) {
            in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9") -> engine.digit(id[0])
            "," -> engine.decimal()
            "+" -> engine.op('+')
            "−" -> engine.op('-')
            "×" -> engine.op('*')
            "÷" -> engine.op('/')
            "xʸ" -> engine.op('^')
            "=" -> engine.equals()
            "clear" -> if (clearKey.icon == KeyView.Icon.BACKSPACE) engine.backspace() else engine.clear()
            "±" -> engine.toggleSign()
            "%" -> engine.percent()
            "(" -> engine.lparen()
            ")" -> engine.rparen()
            "√" -> engine.sqrt()
            "MS" -> engine.memStore()
            "MR" -> engine.memRecall()
            "M+" -> engine.memAdd()
            "M−" -> engine.memSub()
            "more" -> togglePanel()
        }
        if (engine.memory != memBefore) CalcState.save()
        refresh()
    }

    private fun displayLine() = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START
        includeFontPadding = false
        fontFeatureSettings = "tnum"
    }

    // ---------- behaviour ----------

    private fun togglePanel() {
        panelOpen = !panelOpen
        CalcState.panelOpen = panelOpen
        CalcState.save()
        moreKey.fillColor = if (panelOpen) Palette.SERVICE_ACTIVE else Palette.SERVICE
        val target = if (panelOpen) 1f else 0f
        animator?.cancel()
        if (floating) {
            // The window grows/shrinks by exactly two rows; keys keep their size and place.
            fraction = target
            host.onPanelToggled(panelOpen, 2 * cell)
            requestLayout()
        } else {
            animator = ValueAnimator.ofFloat(fraction, target).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { fraction = it.animatedValue as Float; requestLayout() }
                start()
            }
        }
    }

    fun refresh() {
        val evaluated = engine.justEvaluated
        val err = engine.errorMessage
        if (evaluated) {
            line1.text = engine.lastExpression + " ="
            line2.text = engine.expressionText()
        } else {
            line1.text = engine.expressionText()
            line2.text = err ?: engine.preview()?.let { "= $it" } ?: ""
        }
        val showC = evaluated || engine.tokens.isEmpty() || err != null
        clearKey.icon = if (showC) KeyView.Icon.NONE else KeyView.Icon.BACKSPACE
        clearKey.label = "C"
        memMark.visibility = if (engine.memory != null) View.VISIBLE else View.GONE
        requestLayout()
        invalidate()
    }

    // ---------- touch: drag the floating window by any empty area ----------

    private var sx = 0f
    private var sy = 0f

    private inline fun forward(e: MotionEvent, f: (Int, Int, Int) -> Unit) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; f(0, 0, 0) }
            MotionEvent.ACTION_MOVE -> f((e.rawX - sx).toInt(), (e.rawY - sy).toInt(), 1)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> f((e.rawX - sx).toInt(), (e.rawY - sy).toInt(), 2)
        }
    }

    private var resizing = false

    /**
     * Floating window: a touch that no key took moves the window, or resizes it
     * when it starts in the bottom-right corner zone (around the arc).
     */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!floating) return super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val zone = dp(52f)
            resizing = e.x > width - zone && e.y > height - zone
        }
        forward(e) { dx, dy, ph -> if (resizing) host.onResize(dx, dy, ph) else host.onDrag(dx, dy, ph) }
        return true
    }

    // ---------- layout ----------

    private val scratch = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        computeGeometry(w, h)
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val r = rects[v] ?: Rect()
            v.measure(
                MeasureSpec.makeMeasureSpec(max(0, r.width()), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(max(0, r.height()), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val rc = rects[v] ?: continue
            v.layout(rc.left, rc.top, rc.right, rc.bottom)
        }
        post { logPositions() }
    }

    private fun place(v: View, left: Int, top: Int, width: Int, height: Int) {
        val r = rects.getOrPut(v) { Rect() }
        r.set(left, top, left + width, top + height)
    }

    private fun computeGeometry(w: Int, h: Int) {
        val left = paddingLeft + padSide
        val right = w - paddingRight - padSide
        val top = paddingTop + padTop
        val bottom = h - paddingBottom - padBottom
        val availW = max(0, right - left)
        // The floating title bar scales with the window, within comfortable touch limits.
        if (floating) barH = ((availW / 4) * 0.62f).roundToInt().coerceIn(dp(30f), dp(40f))
        val rows = 5f + 2f * fraction
        val availH = (bottom - top - barH - displayMin).toFloat()

        cell = max(dp(20f), min(availW / 4, floor(availH / rows).toInt()))
        val dia = (cell * KEY_RATIO).roundToInt()
        val inset = (cell - dia) / 2
        val gridLeft = left + (availW - cell * 4) / 2
        val mainTop = bottom - cell * 5

        for ((v, r, c) in mainKeys) {
            place(v, gridLeft + c * cell + inset, mainTop + r * cell + inset, dia, dia)
        }
        val panelH = (2 * cell * fraction).roundToInt()
        for ((v, r, c) in panelKeys) {
            val rest = mainTop - (2 - r) * cell
            val y = mainTop + ((rest - mainTop) * fraction).roundToInt()
            place(v, gridLeft + c * cell + inset, y + inset, dia, dia)
            v.alpha = fraction
            v.visibility = if (fraction > 0f) View.VISIBLE else View.INVISIBLE
        }

        // Display: aligned to the outer edges of the key circles.
        val dLeft = gridLeft + inset
        val dRight = gridLeft + cell * 4 - inset
        val dTop = top + barH
        val dBottom = mainTop - panelH
        val dH = max(0, dBottom - dTop)
        val dW = dRight - dLeft
        val bigPx = max(dp(16f).toFloat(), min(dH * 0.40f, dia * 0.82f))
        val smallPx = max(dp(12f).toFloat(), bigPx * 0.42f)
        val bigH = (bigPx * 1.22f).roundToInt()
        val smallH = (smallPx * 1.4f).roundToInt()
        val blockBottom = dBottom - inset
        val evaluated = engine.justEvaluated
        if (!evaluated) {
            styleLine(line1, bigPx, dW, Fonts.thin, Palette.TEXT)
            styleLine(line2, smallPx, dW, Fonts.light, if (engine.errorMessage != null) Palette.OP else Palette.MUTED)
            place(line1, dLeft, blockBottom - smallH - bigH, dW, bigH)
            place(line2, dLeft, blockBottom - smallH, dW, smallH)
        } else {
            styleLine(line1, smallPx, dW, Fonts.light, Palette.MUTED)
            styleLine(line2, bigPx, dW, Fonts.thin, Palette.TEXT)
            place(line1, dLeft, blockBottom - bigH - smallH, dW, smallH)
            place(line2, dLeft, blockBottom - bigH, dW, bigH)
        }
        memMark.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallPx)
        val mH = (smallPx * 1.3f).roundToInt()
        place(memMark, dLeft, blockBottom - bigH - smallH - mH, (smallPx * 1.4f).roundToInt(), mH)

        // Title bar, aligned with the key circles.
        place(closeBtn, dRight - barH + inset, top, barH, barH)
        place(modeBtn, dRight - 2 * barH + inset, top, barH, barH)
        val barLeft = dLeft - inset
        val modeLeft = dRight - 2 * barH + inset
        var seekLeft = barLeft + barH
        if (opacityIcon != null) {
            if (modeLeft - dp(4f) - seekLeft < dp(72f)) {
                place(opacityIcon, barLeft, top, 0, 0)   // narrow window: the slider gets the room
                seekLeft = barLeft
            } else place(opacityIcon, barLeft, top, barH, barH)
        }
        seek?.let { place(it, seekLeft, top, max(0, modeLeft - dp(4f) - seekLeft), barH) }
        corner?.let { val s = dp(40f); place(it, w - s, h - s, s, s) }
    }

    /** Text size: target, shrunk (to 45%) until the line fits; beyond that it ellipsizes at the start. */
    private fun styleLine(tv: TextView, target: Float, width: Int, face: android.graphics.Typeface, color: Int) {
        tv.typeface = face
        tv.setTextColor(color)
        scratch.typeface = face
        var size = target
        val text = tv.text.toString()
        while (size > target * 0.45f) {
            scratch.textSize = size
            if (scratch.measureText(text) <= width) break
            size *= 0.94f
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    // ---------- diagnostics for the emulator test ----------

    private var lastSig = ""
    private fun log(msg: String) = Log.d("Calc", msg)

    private fun logPositions() {
        if (!isAttachedToWindow) return
        val loc = IntArray(2)
        val items = ArrayList<Pair<String, View>>()
        mainKeys.forEach { items += it.first.keyId to it.first }
        if (fraction >= 1f) panelKeys.forEach { items += it.first.keyId to it.first }
        items += "mode" to modeBtn
        items += "close" to closeBtn
        seek?.let { items += "seek" to it }
        corner?.let { items += "corner" to it }
        val sb = StringBuilder()
        for ((id, v) in items) {
            v.getLocationOnScreen(loc)
            sb.append("$id ${loc[0]} ${loc[1]} ${loc[0] + v.width} ${loc[1] + v.height}\n")
        }
        val sig = sb.toString()
        if (sig == lastSig) return
        lastSig = sig
        val mode = if (floating) "F" else "A"
        sig.lineSequence().filter { it.isNotBlank() }.forEach { log("pos $mode $it") }
    }

    companion object {
        const val MIN_ALPHA = 15
        private const val KEY_RATIO = 0.86f
    }
}
