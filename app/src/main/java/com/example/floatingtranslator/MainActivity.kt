package com.example.floatingtranslator

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private var resultCode = 0
    private var resultData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        btnStart.setOnClickListener {
            startTranslator()
        }

        btnStop.setOnClickListener {
            stopTranslator()
        }

        updateButtons()
    }

    private fun startTranslator() {
        try {
            // Android 6+ butuh overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, 100)
                    return
                }
            }

            // Request screen capture
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), 200)

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopTranslator() {
        try {
            stopService(Intent(this, FloatingTranslatorService::class.java))
            updateButtons()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startFloatingService() {
        try {
            val intent = Intent(this, FloatingTranslatorService::class.java)
            intent.putExtra("resultCode", resultCode)
            intent.putExtra("resultData", resultData)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            Toast.makeText(this, "Penerjemah dimulai!", Toast.LENGTH_SHORT).show()
            updateButtons()

        } catch (e: Exception) {
            Toast.makeText(this, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateButtons() {
        try {
            val running = FloatingTranslatorService.isRunning
            btnStart.isEnabled = !running
            btnStop.isEnabled = running
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            100 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        startTranslator()
                    }
                }
            }
            200 -> {
                if (resultCode == RESULT_OK && data != null) {
                    this.resultCode = resultCode
                    this.resultData = data
                    startFloatingService()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtons()
    }
}
