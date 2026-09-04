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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        
        btnStart.setOnClickListener {
            if (checkOverlay()) {
                startScreenCapture()
            }
        }
        
        btnStop.setOnClickListener {
            stopService(Intent(this, FloatingTranslatorService::class.java))
            updateButtons()
        }
        
        updateButtons()
    }
    
    private fun checkOverlay(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")),
                    100
                )
                return false
            }
        }
        return true
    }
    
    private fun startScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), 102)
    }
    
    private fun startService(resultCode: Int, data: Intent) {
        val intent = Intent(this, FloatingTranslatorService::class.java)
        intent.putExtra("resultCode", resultCode)
        intent.putExtra("resultData", data)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        Toast.makeText(this, "Penerjemah dimulai!", Toast.LENGTH_SHORT).show()
        updateButtons()
    }
    
    private fun updateButtons() {
        val running = FloatingTranslatorService.isRunning
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            100 -> if (checkOverlay()) startScreenCapture()
            102 -> if (resultCode == RESULT_OK && data != null) startService(resultCode, data)
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateButtons()
    }
}
