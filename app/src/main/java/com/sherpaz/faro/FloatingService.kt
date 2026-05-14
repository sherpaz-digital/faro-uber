package com.sherpaz.faro

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Environment
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var isAnalyzing = false
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            log("BROADCAST recibido")
            val filePath = intent?.getStringExtra(CaptureActivity.EXTRA_FILE_PATH)
            if (filePath != null) {
                log("Archivo recibido: $filePath")
                val bitmap = BitmapFactory.decodeFile(filePath)
                log("Bitmap decodificado: ${bitmap != null} — ${bitmap?.width}x${bitmap?.height}")
                processBitmap(bitmap)
            } else {
                log("ERROR: filePath es null")
                showError("E:BMP")
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "faro_channel_v3"
        const val NOTIF_ID = 1
        const val COLOR_IDLE   = 0xFF444444.toInt()
        const val COLOR_RED    = 0xFFe03030.toInt()
        const val COLOR_YELLOW = 0xFFf5d800.toInt()
        const val COLOR_GREEN  = 0xFF1a9e3a.toInt()
        const val COLOR_PURPLE = 0xFF7c2fc8.toInt()

        var floatingServiceInstance: FloatingService? = null
    }

    private fun log(msg: String) {
        try {
            val logFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "faro_log.txt"
            )
            val time = dateFormat.format(Date())
            FileWriter(logFile, true).use { it.write("[$time] $msg\n") }
        } catch (e: Exception) {
            // silencioso
        }
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

        val filter = IntentFilter(CaptureActivity.ACTION_CAPTURE_DONE)
        registerReceiver(captureReceiver, filter, RECEIVER_NOT_EXPORTED)
        log("Receiver registrado")
    }

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
            makeDraggable(overlayView, params)
            resetCircles()
            log("Overlay creado OK")

            overlayView.findViewById<FrameLayout>(R.id.circleHora).setOnClickListener {
                if (!isAnalyzing) {
                    isAnalyzing = true
                    log("Círculo tocado — iniciando captura")
                    setCircleColor(overlayView.findViewById(R.id.circleHora), COLOR_YELLOW)
                    overlayView.findViewById<TextView>(R.id.tvHora).text = "..."
                    CaptureActivity.start(this)
                }
            }
        } catch (e: Exception) {
            log("ERROR en setupOverlay: ${e.message}")
            stopSelf()
        }
    }

    private fun processBitmap(bitmap: Bitmap?) {
        val prefs = getSharedPreferences("faro_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        log("API key length: ${apiKey.length}")

        if (bitmap == null) {
            log("ERROR: bitmap null")
            showError("E:BMP")
            return
        }

        if (apiKey.isEmpty()) {
            log("ERROR: API key vacía")
            showError("E:KEY")
            return
        }

        scope.launch {
            try {
                log("Llamando a Claude API...")
                val result = analyzeWithClaude(apiKey, bitmap)
                withContext(Dispatchers.Main) {
                    if (result != null) {
                        log("Resultado OK: hora=${result.clpHora} km=${result.clpKm}")
                        updateCircles(result.clpHora, result.clpKm)
                    } else {
                        log("Resultado null — sin datos")
                        resetCircles()
                    }
                }
            } catch (e: Exception) {
                log("EXCEPCIÓN: ${e.message}")
                showError("E:EXC")
            } finally {
                delay(3000)
                isAnalyzing = false
            }
        }
    }

    private fun showError(code: String) {
        scope.launch(Dispatchers.Main) {
            setCircleColor(overlayView.findViewById(R.id.circleHora), COLOR_RED)
            overlayView.findViewById<TextView>(R.id.tvHora).text = code
            delay(4000)
            resetCircles()
            isAnalyzing = false
        }
    }

    private suspend fun analyzeWithClaude(apiKey: String, bitmap: Bitmap): TripData? {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val imageBytes = baos.toByteArray()
        val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        log("Imagen: ${imageBytes.size} bytes")

        val prompt = """Analiza esta captura de pantalla de Uber Driver en Chile.
Devuelve SOLO este JSON con los datos de la solicitud:
{"tarifa":0,"bono_inicio":0,"km_buscar":0.0,"min_buscar":0,"km_viaje":0.0,"min_viaje":0}

- tarifa: monto principal en CLP (entero)
- bono_inicio: bono adicional en CLP (0 si no hay)
- km_buscar: km para buscar pasajero
- min_buscar: minutos para buscar
- km_viaje: km del viaje
- min_viaje: minutos del viaje

Solo JSON, sin texto adicional."""

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

        val requestBody = JSONObject().apply {
            put("model", "claude-haiku-4-5-20251001")
            put("max_tokens", 200)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", messageContent)
            }))
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()

        return try {
            log("Ejecutando request HTTP...")
            val resp = client.newCall(request).execute()
            val httpCode = resp.code
            val respBody = resp.body?.string() ?: ""
            log("HTTP $httpCode — respuesta: ${respBody.take(300)}")

            if (!resp.isSuccessful) {
                withContext(Dispatchers.Main) { showError("H:$httpCode") }
                return null
            }

            val json = JSONObject(respBody)
            if (json.has("error")) {
                log("Error API: ${json.getJSONObject("error").optString("message")}")
                withContext(Dispatchers.Main) { showError("API ERR") }
                return null
            }

            val rawText = json.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
            log("Texto Claude: $rawText")

            val jsonStart = rawText.indexOf('{')
            val jsonEnd = rawText.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1) {
                log("No hay JSON en respuesta")
                return null
            }

            val data = JSONObject(rawText.substring(jsonStart, jsonEnd + 1))
            val tarifa = data.optDouble("tarifa", 0.0).toInt()
            val bono   = data.optDouble("bono_inicio", 0.0).toInt()
            val kmB    = data.optDouble("km_buscar", 0.0)
            val minB   = data.optDouble("min_buscar", 0.0).toInt()
            val kmV    = data.optDouble("km_viaje", 0.0)
            val minV   = data.optDouble("min_viaje", 0.0).toInt()

            log("Datos: tarifa=$tarifa bono=$bono kmB=$kmB minB=$minB kmV=$kmV minV=$minV")

            if (tarifa == 0 && kmV == 0.0) {
                log("Datos vacíos — no hay solicitud en imagen")
                return null
            }

            val total    = tarifa + bono
            val totalMin = (minB + minV).toDouble().coerceAtLeast(1.0)
            val totalKm  = (kmB + kmV).coerceAtLeast(0.1)

            TripData(
                clpHora = ((total / totalMin) * 60).toInt(),
                clpKm   = (total / totalKm).toInt()
            )
        } catch (e: Exception) {
            log("EXCEPCIÓN HTTP: ${e.message}")
            withContext(Dispatchers.Main) { showError("E:NET") }
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
        scope.cancel()
        try { unregisterReceiver(captureReceiver) } catch (e: Exception) {}
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        try { File(cacheDir, "faro_capture.jpg").delete() } catch (e: Exception) {}
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
