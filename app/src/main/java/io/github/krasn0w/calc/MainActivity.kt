package io.github.krasn0w.calc

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Launcher: starts the floating window, or asks for the overlay permission first. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            launch()
            return
        }
        setContentView(permissionView())
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && Settings.canDrawOverlays(this)) launch()
    }

    private fun launch() {
        startForegroundService(Intent(this, OverlayService::class.java))
        finish()
    }

    private fun permissionView(): LinearLayout {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF161618.toInt())
            setPadding(dp(28), dp(28), dp(28), dp(28))
            addView(TextView(context).apply {
                text = "Нужно одно разрешение"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(context).apply {
                text = "Чтобы калькулятор висел поверх других приложений, включите для Calc " +
                    "«Поверх других приложений», затем вернитесь сюда."
                setTextColor(0xFFB0B0B5.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(TextView(context).apply {
                text = "Открыть настройки"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(0xFFFF9500.toInt())
                    cornerRadius = dp(28).toFloat()
                }
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
            }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(28) })
        }
    }
}
