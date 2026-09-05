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
    
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var translatedTextView: TextView? = null
    private var translatedTextFull: TextView? = null
    private var translationArea: View? = null
    private var translator: com.google.mlkit.nl.translate.Translator? = null
    private var textRecognizer: com.google.mlkit.vision.text.TextRecognizer? = null
    
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
    private var serviceIntent: Intent? = null
    private var isSetup = false
    private var isExpanded = false
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceIntent = intent
        
        if (!isSetup) {
            setupService()
        } else {
            setupScreenCapture()
        }
        
        return START_STICKY
    }
    
    private fun setupService() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            createNotificationChannel()
            startForeground(1, createNotification())
            
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build()
            translator = Translation.getClient(options)
            
            translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            
            setupFloatingWindow()
            setupScreenCapture()
            startOCRLoop()
            
            isSetup = true
            isRunning = true
            
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }
    
    private fun setupFloatingWindow() {
        try {
            val wm = windowManager ?: return
            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val view = inflater.inflate(R.layout.floating_translator, null)
            floatingView = view
            
            translatedTextView = view.findViewById(R.id.translatedTextView)
            translatedTextFull = view.findViewById(R.id.translatedTextFull)
            translationArea = view.findViewById(R.id.translationArea)
            val btnCopy = view.findViewById<Button>(R.id.btnCopy)
            val btnClose = view.findViewById<Button>(R.id.btnClose)
            val dragHandle = view.findViewById<View>(R.id.dragHandle)
            
            btnCopy.setOnClickListener {
                if (lastTranslatedText.isNotEmpty()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Translated", lastTranslatedText))
                    Toast.makeText(this, "Teks disalin!", Toast.LENGTH_SHORT).show()
                }
            }
            
            btnClose.setOnClickListener {
                translationArea?.visibility = View.GONE
                isExpanded = false
            }
            
            // Tap untuk expand/collapse
            dragHandle.setOnClickListener {
                if (isExpanded) {
                    translationArea?.visibility = View.GONE
                    isExpanded = false
                } else {
                    translationArea?.visibility = View.VISIBLE
                    isExpanded = true
                }
            }
            
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            
            params.gravity = Gravity.TOP or Gravity.START
            params.x = areaX
            params.y = areaY
            
            wm.addView(view, params)
            
            setupDrag(view, wm, params)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun setupDrag(view: View, wm: WindowManager, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false
        
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - touchX
                    val deltaY = event.rawY - touchY
                    
                    if (Math.abs(deltaX) > 5 || Math.abs(deltaY) > 5) {
                        isDragging = true
                    }
                    
                    if (isDragging) {
                        params.x = initialX + deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        areaX = params.x
                        areaY = params.y
                        wm.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tap - toggle expand
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }
    
    private fun setupScreenCapture() {
        try {
            val wm = windowManager ?: return
            
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
            
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            
            val intent = serviceIntent
            val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
            
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("resultData", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("resultData")
            }
            
            if (resultCode != 0 && resultData != null && mediaProjection == null) {
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
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun startOCRLoop() {
        handler.post(object : Runnable {
            override fun run() {
                captureAndTranslate()
                handler.postDelayed(this, 5000)
            }
        })
    }
    
    private fun captureAndTranslate() {
        if (ocrRunning) return
        ocrRunning = true
        
        try {
            val reader = imageReader ?: run { ocrRunning = false; return }
            val recognizer = textRecognizer ?: run { ocrRunning = false; return }
            val trans = translator ?: run { ocrRunning = false; return }
            
            val image = reader.acquireLatestImage()
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
                
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val detectedText = visionText.text
                        if (detectedText.isNotEmpty()) {
                            trans.translate(detectedText)
                                .addOnSuccessListener { translatedText ->
                                    lastTranslatedText = translatedText
                                    translatedTextView?.post {
                                        translatedTextView?.text = "📝"
                                    }
                                    translatedTextFull?.post {
                                        translatedTextFull?.text = translatedText
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
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, "floating_translator")
            .setContentTitle("🌍 Penerjemah Melayang")
            .setContentText("Geser ke teks untuk menerjemahkan")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        
        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { mediaProjection?.stop() } catch (e: Exception) {}
        try { imageReader?.close() } catch (e: Exception) {}
        try { floatingView?.let { windowManager?.removeView(it) } } catch (e: Exception) {}
        try { translator?.close() } catch (e: Exception) {}
        try { textRecognizer?.close() } catch (e: Exception) {}
    }
}
