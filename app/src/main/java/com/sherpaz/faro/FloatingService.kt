package com.sherpaz.faro

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Base64
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.NumberFormat
import java.util.Locale

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private val fmt = NumberFormat.getNumberInstance(Locale("es", "CL"))
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var isAnalyzing = false

    // MediaProjection
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    companion object {
        const val CHANNEL_ID   = "faro_channel"
        const val NOTIF_ID     = 1
        const val COLOR_IDLE   = 0xFF444444.toInt()
        const val COLOR_RED    = 0xFFe03030.toInt()
        const val COLOR_YELLOW = 0xFFf5d800.toInt()
        const val COLOR_GREEN  = 0xFF1a9e3a.toInt()
        const val COLOR_PURPLE = 0xFF7c2fc8.toInt()
        var floatingServiceInstance: FloatingService? = null
    }

    override fun onCreate() {
        super.onCreate()
        floatingServiceInstance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Recibir permiso MediaProjection desde MainActivity
        val resultCode = intent?.getIntExtra("mp_result_code", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("mp_data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            setupImageReader()
        }
        return START_STICKY
    }

    private fun setupImageReader() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FaroCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
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

        overlayView.findViewById<FrameLayout>(R.id.circleHora).setOnClickListener {
            if (!isAnalyzing) captureAndAnalyze()
        }
    }

    private fun captureAndAnalyze() {
        val prefs = getSharedPreferences("faro_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: return
        if (apiKey.isEmpty()) return

        isAnalyzing = true
        setCircleColor(overlayView.findViewById(R.id.circleHora), COLOR_YELLOW)
        overlayView.findViewById<TextView>(R.id.tvHora).text = "..."

        scope.launch {
            try {
                val bitmap = captureScreen()
                val result = if (bitmap != null) {
                    analyzeWithClaude(apiKey, bitmap)
                } else {
                    null
                }
                withContext(Dispatchers.Main) {
                    if (result != null) updateCircles(result.clpHora, result.clpKm)
                    else resetCircles()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { resetCircles() }
            } finally {
                delay(2000)
                isAnalyzing = false
            }
        }
    }

    private fun captureScreen(): Bitmap? {
        return try {
            val image = imageReader?.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Escalar la imagen para reducir tamaño antes de enviar a Claude
            val maxDim = 1024
            val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else bitmap
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun analyzeWithClaude(apiKey: String, bitmap: Bitmap): TripData? {
        // Convertir bitmap a base64
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        val imageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val prompt = """Eres un asistente para un conductor de Uber en Chile.
Analiza esta captura de pantalla de Uber Driver y extrae los datos de la solicitud de viaje.
Responde ÚNICAMENTE con un JSON válido, sin texto adicional, sin explicaciones, sin bloques de código.
Formato exacto:
{"tarifa":0,"bono_inicio":0,"km_buscar":0.0,"min_buscar":0,"km_viaje":0.0,"min_viaje":0}

- tarifa: monto en CLP del viaje (número entero)
- bono_inicio: bono adicional si existe (0 si no hay)
- km_buscar: kilómetros para ir a buscar al pasajero
- min_buscar: minutos estimados para ir a buscar
- km_viaje: kilómetros del viaje
- min_viaje: minutos estimados del viaje

Si no ves una solicitud de viaje activa, devuelve todos los valores en 0."""

        val messageContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", imageBase64)
                })
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", "claude-haiku-4-5-20251001")
            put("max_tokens", 200)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", messageContent)
            }))
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()

        return try {
            val resp = client.newCall(request).execute()
            val respBody = resp.body?.string() ?: return null
            val json = JSONObject(respBody)

            // Manejar errores de API
            if (json.has("error")) return null

            val text = json.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .removePrefix("```json")
                .removeSuffix("```")
                .trim()

            val data = JSONObject(text)
            val tarifa = data.optInt("tarifa", 0)
            val bono = data.optInt("bono_inicio", 0)
            val kmB = data.optDouble("km_buscar", 0.0)
            val minB = data.optInt("min_buscar", 0)
            val kmV = data.optDouble("km_viaje", 0.0)
            val minV = data.optInt("min_viaje", 0)

            if (tarifa == 0 || kmV == 0.0 || minV == 0) return null

            val total = tarifa + bono
            val totalMin = (minB + minV).toDouble()
            val totalKm = kmB + kmV

            TripData(
                clpHora = ((total / totalMin) * 60).toInt(),
                clpKm = (total / totalKm).toInt()
            )
        } catch (e: Exception) {
            null
        }
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
        else -> COLOR_RED
    }

    private fun colorKm(v: Int) = when {
        v >= 600 -> COLOR_PURPLE
        v >= 500 -> COLOR_GREEN
        v >= 200 -> COLOR_YELLOW
        else -> COLOR_RED
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var sx = 0f; var sy = 0f; var px = 0; var py = 0
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = params.x; py = params.y; true }
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
        floatingServiceInstance = null
        scope.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW)
            .apply { description = getString(R.string.channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Faro activo")
            .setContentText("Toca el círculo superior para analizar")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi).setOngoing(true).build()
    }
}

