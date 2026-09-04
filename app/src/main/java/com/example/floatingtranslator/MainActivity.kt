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
            if (checkOverlayPermission()) {
                requestScreenCapture()
            }
        }
        
        btnStop.setOnClickListener {
            stopService(Intent(this, FloatingTranslatorService::class.java))
            updateButtons()
        }
        
        updateButtons()
    }
    
    private fun checkOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 100)
                return false
            }
        }
        return true
    }
    
    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), 200)
    }
    
    private fun startTranslatorService(resultCode: Int, data: Intent) {
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
            100 -> {
                if (checkOverlayPermission()) {
                    requestScreenCapture()
                }
            }
            200 -> {
                if (resultCode == RESULT_OK && data != null) {
                    startTranslatorService(resultCode, data)
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateButtons()
    }
}
