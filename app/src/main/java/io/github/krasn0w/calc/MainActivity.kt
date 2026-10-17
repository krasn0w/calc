package io.github.krasn0w.calc

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Toast

/** The calculator as a normal full-screen app. "Collapse" turns it into the floating window. */
class MainActivity : Activity(), CalcPanel.Host {

    private lateinit var panel: CalcPanel
    private var wantFloat = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CalcState.init(this)
        // Only one calculator on screen: opening the app closes the floating window.
        stopService(Intent(this, OverlayService::class.java))

        edgeToEdge()
        panel = CalcPanel(this, floating = false, host = this)
        panel.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val s = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(s.left, s.top, s.right, s.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(panel)
    }

    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    override fun onResume() {
        super.onResume()
        if (wantFloat && Settings.canDrawOverlays(this)) {
            wantFloat = false
            goFloat()
            return
        }
        panel.refresh()
    }

    override fun onPause() {
        super.onPause()
        CalcState.save()
    }

    override fun onModeToggle() {
        if (Settings.canDrawOverlays(this)) {
            goFloat()
        } else {
            wantFloat = true
            Toast.makeText(this, "Разрешите Calc «Поверх других приложений»", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun goFloat() {
        CalcState.save()
        startForegroundService(Intent(this, OverlayService::class.java))
        finish()
    }

    override fun onClose() = finish()
}
