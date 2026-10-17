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
import android.view.WindowManager

/** Foreground service that owns the floating calculator window. */
class OverlayService : Service(), CalcPanel.Host {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var panel: CalcPanel? = null
    private var startX = 0
    private var startY = 0
    private var startW = 0
    private var startH = 0

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density + 0.5f).toInt()
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    /** Height of the window with the extra panel closed; the panel adds two rows on top of it. */
    private var baseH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CalcState.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (panel == null) show() else clamp()
        return START_NOT_STICKY
    }

    private fun panelRowsHeight(width: Int): Int {
        // Two rows of cells; a cell is a quarter of the grid width (see CalcPanel geometry).
        val cell = (width - 2 * dp(10f)) / 4
        return 2 * cell
    }

    private fun show() {
        val view = CalcPanel(ContextThemeWrapper(this, android.R.style.Theme_Material), floating = true, host = this)

        val w = CalcState.getInt(KEY_W, dp(264f)).coerceIn(minW(), screenW)
        baseH = CalcState.getInt(KEY_BASE_H, dp(440f)).coerceIn(minBaseH(), screenH)
        val h = (baseH + if (view.panelOpen) panelRowsHeight(w) else 0).coerceAtMost(screenH)

        params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = CalcState.alphaPct.coerceIn(CalcPanel.MIN_ALPHA, 100) / 100f
            x = CalcState.getInt(KEY_X, screenW - w - dp(16f))
            y = CalcState.getInt(KEY_Y, screenH / 6)
        }
        clampPosition()
        wm.addView(view, params)
        panel = view
    }

    private fun minW() = dp(180f)
    private fun minBaseH() = dp(280f)

    private fun clampPosition() {
        params.width = params.width.coerceIn(minW(), screenW)
        params.height = params.height.coerceAtMost(screenH)
        params.x = params.x.coerceIn(0, maxOf(0, screenW - params.width))
        params.y = params.y.coerceIn(0, maxOf(0, screenH - params.height))
    }

    private fun clamp() {
        val v = panel ?: return
        clampPosition()
        wm.updateViewLayout(v, params)
    }

    private fun savePlacement() {
        CalcState.putInts(KEY_X to params.x, KEY_Y to params.y, KEY_W to params.width, KEY_BASE_H to baseH)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel?.post { clamp() }
    }

    // ---------- CalcPanel.Host ----------

    override fun onDrag(dx: Int, dy: Int, phase: Int) {
        val v = panel ?: return
        if (phase == 0) { startX = params.x; startY = params.y; return }
        params.x = (startX + dx).coerceIn(0, maxOf(0, screenW - params.width))
        params.y = (startY + dy).coerceIn(0, maxOf(0, screenH - params.height))
        wm.updateViewLayout(v, params)
        if (phase == 2) savePlacement()
    }

    override fun onResize(dx: Int, dy: Int, phase: Int) {
        val v = panel ?: return
        if (phase == 0) { startW = params.width; startH = params.height; return }
        val extra = params.height - baseH
        params.width = (startW + dx).coerceIn(minW(), screenW - params.x)
        params.height = (startH + dy).coerceIn(minBaseH() + extra, screenH - params.y)
        baseH = params.height - extra
        wm.updateViewLayout(v, params)
        if (phase == 2) {
            // After the new layout, re-derive the panel rows for the new size so toggling stays exact.
            v.post {
                if (v.panelOpen) baseH = params.height - 2 * v.cell
                savePlacement()
            }
        }
    }

    override fun onPanelToggled(open: Boolean, deltaPx: Int) {
        val v = panel ?: return
        val newH = (baseH + if (open) deltaPx else 0).coerceAtMost(screenH)
        // Grow upwards, so the keypad stays exactly where it was under the finger.
        params.y -= newH - params.height
        params.height = newH
        params.y = params.y.coerceIn(0, maxOf(0, screenH - params.height))
        wm.updateViewLayout(v, params)
        savePlacement()
    }

    override fun onAlphaChanged(pct: Int) {
        val v = panel ?: return
        params.alpha = pct / 100f
        wm.updateViewLayout(v, params)
        CalcState.alphaPct = pct
        CalcState.save()
    }

    override fun onModeToggle() {
        CalcState.save()
        savePlacement()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        stopSelf()
    }

    override fun onClose() {
        savePlacement()
        stopSelf()
    }

    override fun onDestroy() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        CalcState.save()
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
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_W = "w"
        private const val KEY_BASE_H = "baseH"
    }
}
