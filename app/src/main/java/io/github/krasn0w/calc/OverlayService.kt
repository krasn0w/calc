package io.github.krasn0w.calc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.math.BigDecimal

/** Foreground service that owns the floating calculator window. */
class OverlayService : Service(), CalcPanel.Host {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var panel: CalcPanel? = null
    private val engine = Engine()
    private val prefs by lazy { getSharedPreferences("calc", MODE_PRIVATE) }
    private var dragStartX = 0
    private var dragStartY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (panel == null) show() else clampToScreen()
        return START_NOT_STICKY
    }

    private fun show() {
        prefs.getString(KEY_MEMORY, null)?.let { s -> runCatching { engine.memory = BigDecimal(s) } }
        val alphaPct = prefs.getInt(KEY_ALPHA, 100).coerceIn(CalcPanel.MIN_ALPHA, 100)
        val open = prefs.getBoolean(KEY_PANEL, false)
        val view = CalcPanel(ContextThemeWrapper(this, android.R.style.Theme_Material), engine, alphaPct, open, this)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = alphaPct / 100f
        }

        val savedX = prefs.getInt(KEY_X, Int.MIN_VALUE)
        val savedY = prefs.getInt(KEY_Y, Int.MIN_VALUE)
        if (savedX == Int.MIN_VALUE || savedY == Int.MIN_VALUE) {
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val dm = resources.displayMetrics
            params.x = dm.widthPixels - view.measuredWidth - (16 * dm.density).toInt()
            params.y = dm.heightPixels / 5
        } else {
            params.x = savedX
            params.y = savedY
        }

        wm.addView(view, params)
        panel = view
        view.post { clampToScreen() }
    }

    private fun clampToScreen(extraHeight: Int = 0) {
        val v = panel ?: return
        val dm = resources.displayMetrics
        val w = if (v.width > 0) v.width else v.measuredWidth
        val h = (if (v.height > 0) v.height else v.measuredHeight) + extraHeight
        params.x = params.x.coerceIn(0, maxOf(0, dm.widthPixels - w))
        params.y = params.y.coerceIn(0, maxOf(0, dm.heightPixels - h))
        wm.updateViewLayout(v, params)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel?.post { clampToScreen() }
    }

    // ---------- CalcPanel.Host ----------

    override fun onDrag(dx: Int, dy: Int, phase: Int) {
        val v = panel ?: return
        if (phase == 0) {
            dragStartX = params.x
            dragStartY = params.y
            return
        }
        val dm = resources.displayMetrics
        params.x = (dragStartX + dx).coerceIn(0, maxOf(0, dm.widthPixels - v.width))
        params.y = (dragStartY + dy).coerceIn(0, maxOf(0, dm.heightPixels - v.height))
        wm.updateViewLayout(v, params)
        if (phase == 2) prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
    }

    override fun onAlphaChanged(pct: Int) {
        val v = panel ?: return
        params.alpha = pct / 100f
        wm.updateViewLayout(v, params)
        prefs.edit().putInt(KEY_ALPHA, pct).apply()
    }

    override fun onPanelToggled(open: Boolean, extraHeightPx: Int) {
        prefs.edit().putBoolean(KEY_PANEL, open).apply()
        if (open) clampToScreen(extraHeightPx)
    }

    override fun onClose() = stopSelf()

    override fun onMemoryChanged() {
        val m = engine.memory
        prefs.edit().apply {
            if (m == null) remove(KEY_MEMORY) else putString(KEY_MEMORY, m.toPlainString())
        }.apply()
    }

    override fun onDestroy() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        super.onDestroy()
    }

    // ---------- foreground notification ----------

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Калькулятор поверх окон", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Калькулятор открыт")
            .setContentText("Висит поверх других приложений")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Закрыть", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val ACTION_STOP = "io.github.krasn0w.calc.STOP"
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 1
        private const val KEY_ALPHA = "alpha"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_PANEL = "panelOpen"
        private const val KEY_MEMORY = "memory"
    }
}
