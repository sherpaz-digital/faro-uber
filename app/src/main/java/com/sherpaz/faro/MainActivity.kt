package com.sherpaz.faro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("faro_prefs", Context.MODE_PRIVATE)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etUmbral = findViewById<EditText>(R.id.etUmbralHora)

        etApiKey.setText(prefs.getString("api_key", ""))
        etUmbral.setText(prefs.getInt("umbral_hora", 13000).toString())

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                prefs.edit().putString("api_key", key).apply()
                Toast.makeText(this, "API Key guardada", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnSaveUmbral).setOnClickListener {
            val u = etUmbral.text.toString().toIntOrNull() ?: 13000
            prefs.edit().putInt("umbral_hora", u).apply()
            Toast.makeText(this, "Umbral guardado: $$u/hora", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Activa primero el permiso flotante", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (prefs.getString("api_key", "").isNullOrEmpty()) {
                Toast.makeText(this, "Ingresa tu API Key primero", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startForegroundService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "Faro iniciado", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "Faro detenido", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val tvO = findViewById<TextView>(R.id.tvOverlayStatus)
        val tvA = findViewById<TextView>(R.id.tvAccessStatus)
        tvO.text = if (Settings.canDrawOverlays(this))
            "Permiso flotante: ACTIVO" else "Permiso flotante: INACTIVO"
        val svc = "${packageName}/${UberAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        tvA.text = if (enabled.contains(svc))
            "Accesibilidad: ACTIVA" else "Accesibilidad: INACTIVA"
    }
}
