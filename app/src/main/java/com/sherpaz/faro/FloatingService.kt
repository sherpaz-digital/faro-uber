package com.sherpaz.faro

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.text.NumberFormat
import java.util.Locale

class FloatingService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private val fmt = NumberFormat.getNumberInstance(Locale("es", "CL"))

    companion object {
        const val CHANNEL_ID   = "faro_channel"
        const val NOTIF_ID     = 1
        const val COLOR_IDLE   = 0xFF444444.toInt()
        const val COLOR_RED    = 0xFFe03030.toInt()
        const val COLOR_YELLOW = 0xFFf5d800.toInt()
        const val COLOR_GREEN  = 0xFF1a9e3a.toInt()
        const val COLOR_PURPLE = 0xFF7c2fc8.toInt()
    }

    override fun onCreate() {
        super.onCreate()
        UberAccessibilityService.floatingServiceInstance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    private fun setupOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_circles, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 60 }
        windowManager.addView(overlayView, params)
        makeDraggable(overlayView, params)
        resetCircles()
    }

    fun updateCircles(clpHora: Int, clpKm: Int) {
        val umbral = getSharedPreferences("faro_prefs", Context.MODE_PRIVATE)
            .getInt("umbral_hora", 13000)
        setCircleColor(overlayView.findViewById(R.id.circleHora), colorHora(clpHora, umbral))
        setCircleColor(overlayView.findViewById(R.id.circleKm), colorKm(clpKm))
        overlayView.findViewById<TextView>(R.id.tvHora).text = fmt.format(clpHora)
        overlayView.findViewById<TextView>(R.id.tvKm).text = fmt.format(clpKm)
    }

    fun resetCircles() {
        setCircleColor(overlayView.findViewById(R.id.circleHora), COLOR_IDLE)
        setCircleColor(overlayView.findViewById(R.id.circleKm), COLOR_IDLE)
        overlayView.findViewById<TextView>(R.id.tvHora).text = "—"
        overlayView.findViewById<TextView>(R.id.tvKm).text = "—"
    }

    private fun setCircleColor(circle: FrameLayout, color: Int) {
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF000000.toInt())
            setStroke(12, color)
        }
    }

    private fun colorHora(v: Int, u: Int) = when {
        v >= u + 4000 -> COLOR_PURPLE
        v >= u + 2000 -> COLOR_GREEN
        v >= u - 2000 -> COLOR_YELLOW
        else          -> COLOR_RED
    }

    private fun colorKm(v: Int) = when {
        v >= 600 -> COLOR_PURPLE
        v >= 500 -> COLOR_GREEN
        v >= 200 -> COLOR_YELLOW
        else     -> COLOR_RED
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var sx = 0f; var sy = 0f; var px = 0; var py = 0
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; px=params.x; py=params.y; true }
                MotionEvent.ACTION_MOVE -> { params.x=px-(e.rawX-sx).toInt(); params.y=py+(e.rawY-sy).toInt(); windowManager.updateViewLayout(view,params); true }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        UberAccessibilityService.floatingServiceInstance = null
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { description = getString(R.string.channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Faro activo")
            .setContentText("Monitoreando solicitudes de Uber")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
