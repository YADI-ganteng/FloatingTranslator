package com.example.floatingtranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val REQUEST_OVERLAY = 100
        private const val REQUEST_NOTIFICATION = 101
        private const val REQUEST_MEDIA_PROJECTION = 102
    }
    
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        
        btnStart.setOnClickListener {
            if (checkOverlayPermission() && checkNotificationPermission()) {
                requestMediaProjection()
            }
        }
        
        btnStop.setOnClickListener {
            stopService(Intent(this, FloatingTranslatorService::class.java))
            Toast.makeText(this, "Penerjemah dihentikan", Toast.LENGTH_SHORT).show()
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
                startActivityForResult(intent, REQUEST_OVERLAY)
                return false
            }
        }
        return true
    }
    
    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION
                )
                return false
            }
        }
        return true
    }
    
    private fun requestMediaProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }
    
    private fun startService(resultCode: Int, data: Intent) {
        val intent = Intent(this, FloatingTranslatorService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("resultData", data)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        Toast.makeText(this, "Penerjemah dimulai! Geser area transparan ke teks", Toast.LENGTH_LONG).show()
        updateButtons()
    }
    
    private fun updateButtons() {
        val isRunning = FloatingTranslatorService.isRunning
        btnStart.isEnabled = !isRunning
        btnStop.isEnabled = isRunning
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        if (checkNotificationPermission()) {
                            requestMediaProjection()
                        }
                    }
                }
            }
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    startService(resultCode, data)
                }
            }
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                requestMediaProjection()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateButtons()
    }
}
