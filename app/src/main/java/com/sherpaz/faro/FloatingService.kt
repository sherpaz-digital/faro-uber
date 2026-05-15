package com.sherpaz.faro

import android.animation.ValueAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private val fmt = NumberFormat.getNumberInstance(Locale("es", "CL"))
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isAnalyzing = false
    private var resetJob: Job? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var isSmall = false
    private val normalSize = 110
    private val smallSize = 77 // 110 * 0.7

    companion object {
        const val CHANNEL_ID = "faro_channel_v3"
        const val NOTIF_ID = 1
        const val COLOR_IDLE   = 0xFF666666.toInt()
        const val COLOR_WHITE  = 0xFFFFFFFF.toInt()
        const val COLOR_RED    = 0xFFe03030.toInt()
        const val COLOR_YELLOW = 0xFFf5d800.toInt()
        const val COLOR_GREEN  = 0xFF1a9e3a.toInt()
        const val COLOR_PURPLE = 0xFF7c2fc8.toInt()

        var floatingServiceInstance: FloatingService? = null
    }

    fun log(msg: String) {
        try {
            val logFile = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "faro_log.txt"
            )
            val time = dateFormat.format(Date())
            FileWriter(logFile, true).use { it.write("[$time] $msg\n") }
        } catch (e: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        floatingServiceInstance = this
        UberAccessibilityService.floatingServiceInstance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        log("=== Faro iniciado ===")
        setupOverlay()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun setupOverlay() {
        try {
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_circles, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 60 }

            windowManager.addView(overlayView, params)
            makeDraggable(overlayView, params)
            resetCircles()
            log("Overlay creado OK")

            // Toque simple círculo superior — captura y análisis
            overlayView.findViewById<FrameLayout>(R.id.circleHora).setOnClickListener {
                if (!isAnalyzing) {
                    isAnalyzing = true
                    resetJob?.cancel()
                    log("Círculo tocado — iniciando captura OCR")
                    animateTouchGlow(overlayView.findViewById(R.id.circleHora))
                    overlayView.findViewById<TextView>(R.id.tvHora).text = "..."
                    overlayView.findViewById<TextView>(R.id.tvMinutos).text = ""
                    val accessService = UberAccessibilityService.currentInstance
                    if (accessService != null) {
                        accessService.captureAndAnalyze()
                    } else {
                        log("ERROR: AccessibilityService no activo")
                        showError("E:SVC")
                    }
                }
            }

            // Toque simple círculo inferior — también dispara captura
            overlayView.findViewById<FrameLayout>(R.id.circleKm).setOnClickListener {
                // mismo comportamiento que el superior por ahora
            }

            // Doble toque círculo inferior — achicar/agrandar 30%
            overlayView.findViewById<FrameLayout>(R.id.circleKm).setOnLongClickListener {
                toggleSize()
                true
            }

        } catch (e: Exception) {
            log("ERROR en setupOverlay: ${e.message}")
            stopSelf()
        }
    }

    private fun toggleSize() {
        isSmall = !isSmall
        val targetDp = if (isSmall) smallSize else normalSize
        val targetPx = dpToPx(targetDp)
        val targetTextMain = if (isSmall) 16f else 22f
        val targetTextSub = if (isSmall) 8f else 11f

        listOf(R.id.circleHora, R.id.circleKm).forEach { id ->
            val circle = overlayView.findViewById<FrameLayout>(id)
            val lp = circle.layoutParams
            lp.width = targetPx
            lp.height = targetPx
            circle.layoutParams = lp
        }

        overlayView.findViewById<TextView>(R.id.tvHora).textSize = targetTextMain
        overlayView.findViewById<TextView>(R.id.tvKm).textSize = targetTextMain
        overlayView.findViewById<TextView>(R.id.tvMinutos).textSize = targetTextSub
        overlayView.findViewById<TextView>(R.id.tvKmTotal).textSize = targetTextSub
    }

    private fun animateTouchGlow(circle: FrameLayout) {
        val animator = ValueAnimator.ofFloat(6f, 18f, 6f)
        animator.duration = 600
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            val stroke = anim.animatedValue as Float
            circle.background = buildCircleDrawable(COLOR_WHITE, stroke, true)
        }
        animator.start()
    }

    private fun buildCircleDrawable(color: Int, strokePx: Float, glow: Boolean): Drawable {
        return object : Drawable() {
            override fun draw(canvas: Canvas) {
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                val radius = (bounds.width() / 2f) - strokePx / 2f

                // Fondo negro
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    this.color = Color.BLACK
                }
                canvas.drawCircle(cx, cy, radius, bgPaint)

                // Borde neón con glow
                if (glow && color != COLOR_IDLE) {
                    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        this.color = color
                        this.strokeWidth = strokePx * 2.5f
                        maskFilter = BlurMaskFilter(strokePx * 2f, BlurMaskFilter.Blur.NORMAL)
                        alpha = 160
                    }
                    canvas.drawCircle(cx, cy, radius, glowPaint)
                }

                // Borde principal
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    this.color = color
                    this.strokeWidth = strokePx
                }
                canvas.drawCircle(cx, cy, radius, borderPaint)

                // Línea blanca delgada interior (solo en estado idle)
                if (color == COLOR_IDLE) {
                    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        this.color = COLOR_WHITE
                        this.strokeWidth = 1.5f
                        alpha = 180
                    }
                    canvas.drawCircle(cx, cy, radius - strokePx / 2f - 2f, whitePaint)
                }
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(cf: ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }

    private fun setCircleNeon(circle: FrameLayout, color: Int, active: Boolean) {
        val strokePx = if (active) 14f else 10f
        circle.background = buildCircleDrawable(color, strokePx, active)
    }

    fun showErrorPublic(code: String) = showError(code)
    fun setAnalyzingDone() { isAnalyzing = false }

    private fun showError(code: String) {
        scope.launch(Dispatchers.Main) {
            setCircleNeon(overlayView.findViewById(R.id.circleHora), COLOR_RED, true)
            overlayView.findViewById<TextView>(R.id.tvHora).text = code
            overlayView.findViewById<TextView>(R.id.tvMinutos).text = ""
            delay(4000)
            resetCircles()
            isAnalyzing = false
        }
    }

    fun updateCircles(clpHora: Int, clpKm: Int, minTotales: Int, kmTotales: Double) {
        val umbral = getSharedPreferences("faro_prefs", Context.MODE_PRIVATE)
            .getInt("umbral_hora", 13000)

        setCircleNeon(overlayView.findViewById(R.id.circleHora), colorHora(clpHora, umbral), true)
        setCircleNeon(overlayView.findViewById(R.id.circleKm), colorKm(clpKm), true)

        overlayView.findViewById<TextView>(R.id.tvHora).text = fmt.format(clpHora)
        overlayView.findViewById<TextView>(R.id.tvKm).text = fmt.format(clpKm)

        val kmStr = if (kmTotales == kmTotales.toLong().toDouble())
            kmTotales.toLong().toString()
        else
            String.format("%.1f", kmTotales)

        overlayView.findViewById<TextView>(R.id.tvMinutos).text = "$minTotales"
        overlayView.findViewById<TextView>(R.id.tvKmTotal).text = kmStr

        resetJob?.cancel()
        resetJob = scope.launch(Dispatchers.Main) {
            delay(7000)
            resetCircles()
        }
    }

    fun resetCircles() {
        setCircleNeon(overlayView.findViewById(R.id.circleHora), COLOR_IDLE, false)
        setCircleNeon(overlayView.findViewById(R.id.circleKm), COLOR_IDLE, false)
        overlayView.findViewById<TextView>(R.id.tvHora).text = "—"
        overlayView.findViewById<TextView>(R.id.tvKm).text = "—"
        overlayView.findViewById<TextView>(R.id.tvMinutos).text = ""
        overlayView.findViewById<TextView>(R.id.tvKmTotal).text = ""
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
        var lastClick = 0L
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; px = params.x; py = params.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = px - (e.rawX - sx).toInt()
                    params.y = py + (e.rawY - sy).toInt()
                    windowManager.updateViewLayout(view, params); true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        log("Faro detenido")
        floatingServiceInstance = null
        UberAccessibilityService.floatingServiceInstance = null
        resetJob?.cancel()
        scope.cancel()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "Faro", NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Faro activo")
            .setContentText("Toca el círculo superior para analizar")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }
}
