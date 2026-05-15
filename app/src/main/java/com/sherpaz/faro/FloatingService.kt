package com.sherpaz.faro

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
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
    private val smallSize = 77
    private var currentColorHora = COLOR_IDLE
    private var currentColorKm = COLOR_IDLE

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
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 60 }

            windowManager.addView(overlayView, params)
            resetCircles()
            log("Overlay creado OK")

            setupCircleTouch(overlayView, params)

        } catch (e: Exception) {
            log("ERROR en setupOverlay: ${e.message}")
            stopSelf()
        }
    }

    private fun setupCircleTouch(view: View, params: WindowManager.LayoutParams) {
        var sx = 0f; var sy = 0f; var px = 0; var py = 0
        var isDragging = false
        var lastClickKm = 0L

        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY
                    px = params.x; py = params.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = px - dx.toInt()
                        params.y = py + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        val circleHora = view.findViewById<FrameLayout>(R.id.circleHora)
                        val circleKm = view.findViewById<FrameLayout>(R.id.circleKm)
                        val loc = IntArray(2)

                        circleHora.getLocationOnScreen(loc)
                        val horaLeft = loc[0]; val horaTop = loc[1]
                        val horaRight = horaLeft + circleHora.width
                        val horaBottom = horaTop + circleHora.height

                        circleKm.getLocationOnScreen(loc)
                        val kmLeft = loc[0]; val kmTop = loc[1]
                        val kmRight = kmLeft + circleKm.width
                        val kmBottom = kmTop + circleKm.height

                        val tx = e.rawX.toInt(); val ty = e.rawY.toInt()

                        when {
                            tx in horaLeft..horaRight && ty in horaTop..horaBottom -> {
                                if (!isAnalyzing) {
                                    isAnalyzing = true
                                    resetJob?.cancel()
                                    log("Círculo hora tocado — captura OCR")
                                    animateTouchGlow(circleHora, currentColorHora)
                                    view.findViewById<TextView>(R.id.tvHora).text = "..."
                                    view.findViewById<TextView>(R.id.tvMinutos).text = ""
                                    val accessService = UberAccessibilityService.currentInstance
                                    if (accessService != null) {
                                        accessService.captureAndAnalyze()
                                    } else {
                                        log("ERROR: AccessibilityService no activo")
                                        showError("E:SVC")
                                    }
                                }
                            }
                            tx in kmLeft..kmRight && ty in kmTop..kmBottom -> {
                                val now = System.currentTimeMillis()
                                if (now - lastClickKm < 400) {
                                    toggleSize()
                                } else {
                                    animateTouchGlow(circleKm, currentColorKm)
                                }
                                lastClickKm = now
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleSize() {
        isSmall = !isSmall
        val targetPx = dpToPx(if (isSmall) smallSize else normalSize)
        val targetTextMain = if (isSmall) 16f else 22f
        val targetTextSub = if (isSmall) 10f else 14f

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

    private fun animateTouchGlow(circle: FrameLayout, restoreColor: Int) {
        val animator = ValueAnimator.ofFloat(8f, 14f, 8f)
        animator.duration = 400
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            val stroke = (anim.animatedValue as Float).toInt()
            circle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF000000.toInt())
                setStroke(stroke, COLOR_WHITE)
            }
        }
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationEnd(a: Animator) {
                setCircleColor(circle, restoreColor)
            }
            override fun onAnimationStart(a: Animator) {}
            override fun onAnimationCancel(a: Animator) {}
            override fun onAnimationRepeat(a: Animator) {}
        })
        animator.start()
    }

    private fun setCircleColor(circle: FrameLayout, color: Int) {
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF000000.toInt())
            setStroke(12, color)
        }
    }

    fun showErrorPublic(code: String) = showError(code)
    fun setAnalyzingDone() { isAnalyzing = false }

    private fun showError(code: String) {
        scope.launch(Dispatchers.Main) {
            currentColorHora = COLOR_RED
            setCircleColor(overlayView.findViewById(R.id.circleHora), COLOR_RED)
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

        currentColorHora = colorHora(clpHora, umbral)
        currentColorKm = colorKm(clpKm)

        setCircleColor(overlayView.findViewById(R.id.circleHora), currentColorHora)
        setCircleColor(overlayView.findViewById(R.id.circleKm), currentColorKm)

        overlayView.findViewById<TextView>(R.id.tvHora).text = fmt.format(clpHora)
        overlayView.findViewById<TextView>(R.id.tvKm).text = fmt.format(clpKm)

        val kmStr = if (kmTotales == kmTotales.toLong().toDouble())
            kmTotales.toLong().toString()
        else
            String.format("%.1f", kmTotales)

        overlayView.findViewById<TextView>(R.id.tvMinutos).text = "$minTotales min"
        overlayView.findViewById<TextView>(R.id.tvKmTotal).text = "$kmStr km"

        resetJob?.cancel()
        resetJob = scope.launch(Dispatchers.Main) {
            delay(7000)
            resetCircles()
        }
    }

    fun resetCircles() {
        currentColorHora = COLOR_IDLE
        currentColorKm = COLOR_IDLE
        setCircleColor(overlayView.findViewById(R.id.circleHora), COLOR_IDLE)
        setCircleColor(overlayView.findViewById(R.id.circleKm), COLOR_IDLE)
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
