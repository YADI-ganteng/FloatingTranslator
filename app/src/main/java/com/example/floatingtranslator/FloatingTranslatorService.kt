package com.example.floatingtranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class FloatingTranslatorService : Service() {
    
    companion object {
        @JvmStatic
        var isRunning = false
    }
    
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var translatedTextView: TextView
    private lateinit var translator: com.google.mlkit.nl.translate.Translator
    private lateinit var textRecognizer: com.google.mlkit.vision.text.TextRecognizer
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    private val handler = Handler(Looper.getMainLooper())
    private var lastTranslatedText = ""
    private var areaX = 0
    private var areaY = 100
    private var ocrRunning = false
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        createNotificationChannel()
        startForeground(1, createNotification())
        
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build()
        translator = Translation.getClient(options)
        
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
        
        setupFloatingWindow()
        setupScreenCapture()
        startOCRLoop()
    }
    
    private fun setupFloatingWindow() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_translator, null)
        
        translatedTextView = floatingView.findViewById(R.id.translatedTextView)
        val btnCopy = floatingView.findViewById<Button>(R.id.btnCopy)
        val btnClose = floatingView.findViewById<View>(R.id.btnClose)
        
        btnCopy.setOnClickListener {
            if (lastTranslatedText.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Translated", lastTranslatedText))
                Toast.makeText(this, "Teks disalin!", Toast.LENGTH_SHORT).show()
            }
        }
        
        btnClose.setOnClickListener {
            stopSelf()
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        params.gravity = Gravity.TOP or Gravity.START
        params.x = areaX
        params.y = areaY
        
        windowManager.addView(floatingView, params)
        
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        
        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    areaX = params.x
                    areaY = params.y
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }
    
    private fun setupScreenCapture() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        
        // FIX: Gunakan getIntent() bukan intent langsung
        val serviceIntent = getIntent()
        val resultCode = serviceIntent?.getIntExtra("resultCode", 0) ?: 0
        
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            serviceIntent?.getParcelableExtra("resultData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            serviceIntent?.getParcelableExtra("resultData")
        }
        
        if (resultCode != 0 && resultData != null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, resultData)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "FloatingTranslator",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
        }
    }
    
    private fun startOCRLoop() {
        handler.post(object : Runnable {
            override fun run() {
                captureAndTranslate()
                handler.postDelayed(this, 3000)
            }
        })
    }
    
    private fun captureAndTranslate() {
        if (ocrRunning) return
        ocrRunning = true
        
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenWidth
                
                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val detectedText = visionText.text
                        if (detectedText.isNotEmpty()) {
                            translator.translate(detectedText)
                                .addOnSuccessListener { translatedText ->
                                    lastTranslatedText = translatedText
                                    translatedTextView.post {
                                        translatedTextView.text = translatedText
                                    }
                                }
                        }
                    }
                    .addOnCompleteListener {
                        image.close()
                        bitmap.recycle()
                        ocrRunning = false
                    }
            } else {
                ocrRunning = false
            }
        } catch (e: Exception) {
            ocrRunning = false
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "floating_translator",
                "Penerjemah Melayang",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, "floating_translator")
            .setContentTitle("Penerjemah Melayang")
            .setContentText("Geser area transparan ke teks")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        translator.close()
        textRecognizer.close()
    }
}
