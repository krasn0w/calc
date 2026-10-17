package io.github.krasn0w.calc

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.View
import kotlin.math.min

private fun ovalRipple(color: Int) = RippleDrawable(ColorStateList.valueOf(color), null, ShapeDrawable(OvalShape()))

/** A calculator key: an exact circle (diameter = the smaller side) with an optically centred label. */
@SuppressLint("ViewConstructor")
class KeyView(context: Context, val keyId: String, label: String, val kind: Kind) : View(context) {

    enum class Kind(val bg: Int, val fg: Int, val ripple: Int, val textScale: Float) {
        DIGIT(Palette.DIGIT, Palette.DIGIT_TEXT, 0x30000000, 0.42f),
        OP(Palette.OP, Palette.TEXT, 0x50FFFFFF, 0.46f),
        SERVICE(Palette.SERVICE, Palette.TEXT, 0x40FFFFFF, 0.38f),
        PANEL(Palette.SERVICE, Palette.TEXT, 0x40FFFFFF, 0.31f),
    }

    enum class Icon { NONE, BACKSPACE, MORE }

    var label: String = label
        set(v) { if (field != v) { field = v; invalidate() } }
    var icon: Icon = Icon.NONE
        set(v) { if (field != v) { field = v; invalidate() } }
    var fillColor: Int = kind.bg
        set(v) { if (field != v) { field = v; invalidate() } }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Fonts.light
        color = kind.fg
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = kind.fg
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kind.fg }
    private val bounds = Rect()
    private val path = Path()

    init {
        foreground = ovalRipple(kind.ripple)
        isClickable = true
        isHapticFeedbackEnabled = true
        contentDescription = keyId
    }

    /** Only the circle itself is the key; touches outside it fall through to the window (drag/resize). */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val r = min(width, height) / 2f
            val dx = e.x - width / 2f
            val dy = e.y - height / 2f
            if (dx * dx + dy * dy > r * r) return false
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        fill.color = fillColor
        c.drawCircle(cx, cy, size / 2f, fill)

        when (icon) {
            Icon.NONE -> {
                // A comma is a tiny glyph; give it the optical weight of a digit.
                text.textSize = size * kind.textScale * (if (label == ",") 1.5f else 1f)
                text.getTextBounds(label, 0, label.length, bounds)
                c.drawText(label, cx - bounds.exactCenterX(), cy - bounds.exactCenterY(), text)
            }
            Icon.BACKSPACE -> {
                stroke.strokeWidth = size * 0.022f
                val w = size * 0.40f
                val h = size * 0.26f
                val l = cx - w / 2f
                val t = cy - h / 2f
                path.reset()
                path.moveTo(l, cy)
                path.lineTo(l + h / 2f, t)
                path.lineTo(l + w, t)
                path.lineTo(l + w, t + h)
                path.lineTo(l + h / 2f, t + h)
                path.close()
                c.drawPath(path, stroke)
                val xc = l + h / 2f + (w - h / 2f) / 2f
                val a = h * 0.22f
                c.drawLine(xc - a, cy - a, xc + a, cy + a, stroke)
                c.drawLine(xc - a, cy + a, xc + a, cy - a, stroke)
            }
            Icon.MORE -> {
                val r = size * 0.028f
                val step = size * 0.13f
                for (i in -1..1) c.drawCircle(cx + i * step, cy, r, dot)
            }
        }
    }
}

/** Thin line icons for the title bar. */
@SuppressLint("ViewConstructor")
class IconButton(context: Context, val keyId: String, glyph: Glyph, clickable: Boolean = true) : View(context) {

    enum class Glyph { CLOSE, TO_WINDOW, TO_FULL, OPACITY }

    var glyph: Glyph = glyph
        set(v) { if (field != v) { field = v; invalidate() } }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Palette.ICON
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ICON }
    private val rect = RectF()

    init {
        if (clickable) {
            foreground = ovalRipple(0x40FFFFFF)
            isClickable = true
        }
        contentDescription = keyId
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        stroke.strokeWidth = maxOf(1.5f * resources.displayMetrics.density, s * 0.035f)
        val g = s * 0.20f // half-size of the glyph box
        when (glyph) {
            Glyph.CLOSE -> {
                val a = g * 0.85f
                c.drawLine(cx - a, cy - a, cx + a, cy + a, stroke)
                c.drawLine(cx - a, cy + a, cx + a, cy - a, stroke)
            }
            Glyph.TO_WINDOW -> {
                // Screen with a small window in the corner ("collapse to a floating window").
                rect.set(cx - g * 1.1f, cy - g * 0.85f, cx + g * 1.1f, cy + g * 0.85f)
                c.drawRoundRect(rect, g * 0.2f, g * 0.2f, stroke)
                rect.set(cx + g * 0.05f, cy + g * 0.05f, cx + g * 0.8f, cy + g * 0.55f)
                c.drawRoundRect(rect, g * 0.08f, g * 0.08f, solid)
            }
            Glyph.TO_FULL -> {
                // Four corner brackets ("expand to full screen").
                val a = g * 0.95f
                val b = g * 0.45f
                for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                    val x = cx + sx * a
                    val y = cy + sy * a
                    c.drawLine(x, y, x - sx * b, y, stroke)
                    c.drawLine(x, y, x, y - sy * b, stroke)
                }
            }
            Glyph.OPACITY -> {
                val r = g * 0.8f
                c.drawCircle(cx, cy, r, stroke)
                rect.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(rect, 90f, 180f, true, solid)
            }
        }
    }
}

/** Resize grip: a thin arc that follows the window's rounded bottom-right corner. */
class CornerView(context: Context, private val windowRadius: Float) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.ICON
    }
    private val oval = RectF()

    init { contentDescription = "corner" }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        stroke.strokeWidth = 2f * d
        val inset = 5f * d
        val r = windowRadius - inset
        val ccx = width - windowRadius
        val ccy = height - windowRadius
        oval.set(ccx - r, ccy - r, ccx + r, ccy + r)
        c.drawArc(oval, 8f, 74f, false, stroke)
    }
}
