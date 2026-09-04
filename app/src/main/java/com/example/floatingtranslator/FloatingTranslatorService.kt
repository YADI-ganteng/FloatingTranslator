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
        const val CHANNEL_ID = "floating_translator_channel"
        const val NOTIFICATION_ID = 1
        var isRunning = false
    }
    
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var translatedTextView: TextView
    private lateinit var btnCopy: Button
    private lateinit var translator: com.google.mlkit.nl.translate.Translator
    private lateinit var textRecognizer: com.google.mlkit.vision.text.TextRecognizer
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0
    
    private val handler = Handler(Looper.getMainLooper())
    private var ocrRunnable: Runnable? = null
    private var isProcessing = false
    private var lastTranslatedText = ""
    
    private var areaX = 0
    private var areaY = 100
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build()
        translator = Translation.getClient(options)
        
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
        
        setupFloatingWindow()
        setupScreenCapture()
        startOCRLoop()
    }
    
    private fun setupFloatingWindow() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_translator, null)
        
        translatedTextView = floatingView.findViewById(R.id.translatedTextView)
        btnCopy = floatingView.findViewById(R.id.btnCopy)
        val btnClose = floatingView.findViewById<View>(R.id.btnClose)
        
        btnCopy.setOnClickListener {
            if (lastTranslatedText.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Translated Text", lastTranslatedText)
                clipboard.setPrimaryClip(clip)
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
        setupDragListener(floatingView, params)
    }
    
    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    areaX = params.x
                    areaY = params.y
                    windowManager.updateViewLayout(view, params)
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
        
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )
        
        setupMediaProjection()
    }
    
    private fun setupMediaProjection() {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("resultData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("resultData")
        }
        
        if (resultCode != 0 && resultData != null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, resultData)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "FloatingTranslator",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        }
    }
    
    private fun startOCRLoop() {
        ocrRunnable = object : Runnable {
            override fun run() {
                if (!isProcessing) {
                    captureAndTranslate()
                }
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(ocrRunnable)
    }
    
    private fun captureAndTranslate() {
        isProcessing = true
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
                
                val cropWidth = 300
                val cropHeight = 150
                val cropX = areaX.coerceIn(0, screenWidth - cropWidth)
                val cropY = areaY.coerceIn(0, screenHeight - cropHeight)
                
                val croppedBitmap = Bitmap.createBitmap(
                    bitmap, cropX, cropY, cropWidth, cropHeight
                )
                
                val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
                
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        if (visionText.text.isNotEmpty()) {
                            translateText(visionText.text)
                        }
                    }
                    .addOnCompleteListener {
                        image.close()
                        bitmap.recycle()
                        croppedBitmap.recycle()
                        isProcessing = false
                    }
            } else {
                isProcessing = false
            }
        } catch (e: Exception) {
            isProcessing = false
        }
    }
    
    private fun translateText(text: String) {
        translator.translate(text)
            .addOnSuccessListener { translatedText ->
                lastTranslatedText = translatedText
                translatedTextView.post {
                    translatedTextView.text = translatedText
                }
            }
            .addOnFailureListener {
                translatedTextView.post {
                    translatedTextView.text = "Gagal menerjemahkan"
                }
            }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Penerjemah Melayang",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Penerjemah Melayang")
            .setContentText("Geser area transparan ke teks")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val resultCode = intent.getIntExtra("resultCode", 0)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("resultData", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("resultData")
            }
            
            if (resultCode != 0 && resultData != null && mediaProjection == null) {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = manager.getMediaProjection(resultCode, resultData)
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "FloatingTranslator",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, null
                )
            }
        }
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        ocrRunnable?.let { handler.removeCallbacks(it) }
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
