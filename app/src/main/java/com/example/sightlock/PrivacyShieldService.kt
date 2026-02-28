package com.example.sightlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PrivacyShieldService : LifecycleService() {

    private lateinit var cameraExecutor: ExecutorService
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayShowing = false

    // Battery optimization: detection state
    private var isDetectionPaused = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var faceAnalyzer: FaceAnalyzer? = null

    // Battery optimization: proximity sensor
    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var isNearProximity = false

    companion object {
        private const val TAG = "PrivacyShieldService"
        private const val NOTIFICATION_CHANNEL_ID = "privacy_shield_channel"
        private const val NOTIFICATION_ID = 1

        // Throttle intervals (ms)
        private const val NORMAL_ANALYSIS_INTERVAL_MS = 500L    // 2 FPS
        private const val OVERLAY_ANALYSIS_INTERVAL_MS = 2000L  // 0.5 FPS when overlay is already showing

        fun startService(context: Context) {
            val intent = Intent(context, PrivacyShieldService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PrivacyShieldService::class.java)
            context.stopService(intent)
        }
    }

    // ---------- Screen state receiver ----------
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF — pausing detection")
                    pauseDetection()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen ON — resuming detection")
                    resumeDetection()
                }
            }
        }
    }

    // ---------- Proximity sensor listener ----------
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val wasNear = isNearProximity
            isNearProximity = event.values[0] < maxRange

            if (isNearProximity && !wasNear) {
                Log.d(TAG, "Proximity NEAR — pausing detection (phone pocketed/face-down)")
                pauseDetection()
            } else if (!isNearProximity && wasNear) {
                Log.d(TAG, "Proximity FAR — resuming detection")
                resumeDetection()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createOverlayView()

        // Register screen state receiver
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, screenFilter)

        // Register proximity sensor
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensor?.let {
            sensorManager.registerListener(
                proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, createNotification())

        // Only start camera if the screen is currently on
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive && !isNearProximity) {
            startCamera()
        } else {
            Log.d(TAG, "Screen off or pocket — deferring camera start")
            isDetectionPaused = true
        }

        return Service.START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        removeOverlay()

        // Unregister screen receiver
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Screen receiver already unregistered")
        }

        // Unregister proximity sensor
        sensorManager.unregisterListener(proximityListener)

        // Release camera
        cameraProvider?.unbindAll()
    }

    // ---------- Detection pause / resume ----------
    private fun pauseDetection() {
        if (isDetectionPaused) return
        isDetectionPaused = true
        cameraProvider?.unbindAll()
        Log.d(TAG, "Detection PAUSED — camera released")
    }

    private fun resumeDetection() {
        if (!isDetectionPaused) return
        isDetectionPaused = false

        // Don't resume if still near proximity
        if (isNearProximity) return

        startCamera()
        Log.d(TAG, "Detection RESUMED — camera restarted")
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Privacy Shield Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Privacy Shield Active")
            .setContentText("Monitoring for peeking eyes...")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this@PrivacyShieldService)

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                faceAnalyzer = FaceAnalyzer(NORMAL_ANALYSIS_INTERVAL_MS) { facesCount ->
                    handleFacesDetected(facesCount)
                }

                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, faceAnalyzer!!)
                    }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                provider.unbindAll()
                provider.bindToLifecycle(
                    this@PrivacyShieldService, cameraSelector, imageAnalyzer!!
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this@PrivacyShieldService))
    }

    private fun handleFacesDetected(facesCount: Int) {
        ContextCompat.getMainExecutor(this@PrivacyShieldService).execute {
            if (facesCount > 1) {
                if (!isOverlayShowing) {
                    showOverlay()
                    // Slow down detection while overlay is showing — save battery
                    faceAnalyzer?.setInterval(OVERLAY_ANALYSIS_INTERVAL_MS)
                }
            } else {
                if (isOverlayShowing) {
                    hideOverlay()
                    // Speed detection back up
                    faceAnalyzer?.setInterval(NORMAL_ANALYSIS_INTERVAL_MS)
                }
            }
        }
    }

    private fun createOverlayView() {
        val themedContext = android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        overlayView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_privacy_shield, null)
    }

    private fun showOverlay() {
        if (overlayView == null) return

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        // API 31+ Glassmorphism Blur
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            
            // Animate blur in
            val blurAnim = android.animation.ValueAnimator.ofInt(0, 50)
            blurAnim.duration = 300
            blurAnim.addUpdateListener { animator ->
                try {
                    params.setBlurBehindRadius(animator.animatedValue as Int)
                    if (isOverlayShowing) windowManager?.updateViewLayout(overlayView, params)
                } catch (e: Exception) {
                    // Ignore transient layout updates errors
                }
            }
            blurAnim.start()
        }

        try {
            windowManager?.addView(overlayView, params)
            isOverlayShowing = true
            isHiding = false
            startOverlayAnimations()
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay: ${e.message}")
        }
    }

    private fun startOverlayAnimations() {
        overlayView?.let { root ->
            // Background dim/blur fade in
            val bg = root.background
            if (bg != null) {
                val alphaAnim = android.animation.ValueAnimator.ofInt(0, 255)
                alphaAnim.duration = 300
                alphaAnim.addUpdateListener { animator ->
                    bg.alpha = animator.animatedValue as Int
                }
                alphaAnim.start()
            }

            // Pulse the shield glow
            root.findViewById<View>(R.id.shield_glow)?.let { glow ->
                val pulseAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse_glow)
                glow.startAnimation(pulseAnim)
            }

            // Bounce-in the warning card
            root.findViewById<View>(R.id.warning_card)?.let { card ->
                val bounceAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.scale_in_bounce)
                card.startAnimation(bounceAnim)
            }

            // Slide text up
            val slideUpAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.slide_in_up)
            root.findViewById<View>(R.id.alert_header)?.startAnimation(slideUpAnim)
            root.findViewById<View>(R.id.main_message)?.startAnimation(slideUpAnim)
            root.findViewById<View>(R.id.sub_message)?.startAnimation(slideUpAnim)

            // Blink the status dot
            root.findViewById<View>(R.id.status_dot)?.let { dot ->
                val blinkAnim = android.view.animation.AlphaAnimation(1.0f, 0.2f).apply {
                    duration = 600
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = android.view.animation.Animation.INFINITE
                }
                dot.startAnimation(blinkAnim)
            }
        }
    }

    private var isHiding = false

    private fun hideOverlay() {
        if (!isOverlayShowing || isHiding) return
        isHiding = true

        executeSecureTransitionAndHide()
    }

    private fun executeSecureTransitionAndHide() {
        overlayView?.let { root ->
            // Stage 1: Threat Cleared (Red -> Green)
            root.findViewById<android.widget.ImageView>(R.id.shield_icon)?.setImageResource(R.drawable.ic_shield_secure)
            root.findViewById<View>(R.id.shield_glow)?.setBackgroundResource(R.drawable.shield_icon_circle_green)
            
            root.findViewById<android.widget.TextView>(R.id.alert_header)?.apply {
                text = "SECURE"
                setTextColor(android.graphics.Color.parseColor("#22C55E")) // Green
            }
            root.findViewById<android.widget.TextView>(R.id.main_message)?.text = "User verified"
            root.findViewById<android.widget.TextView>(R.id.sub_message)?.text = "Privacy threat cleared.\nReturning to normal."

            // Quick pulse on the green transition
            val bounceAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.scale_in_bounce)
            root.findViewById<View>(R.id.shield_glow)?.startAnimation(bounceAnim)

            // Stage 2: Wait 800ms, then animate out
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val card = root.findViewById<View>(R.id.warning_card)
                val fadeOutAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_out_shrink)
                
                // Fade out background
                val bg = root.background
                if (bg != null) {
                    val alphaAnim = android.animation.ValueAnimator.ofInt(255, 0)
                    alphaAnim.duration = 300
                    alphaAnim.addUpdateListener { animator ->
                        bg.alpha = animator.animatedValue as Int
                    }
                    alphaAnim.start()
                }

                fadeOutAnim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        try {
                            windowManager?.removeView(overlayView)
                            isOverlayShowing = false
                            isHiding = false
                        } catch (e: Exception) {
                            Log.e(TAG, "Error removing overlay: ${e.message}")
                        }
                    }
                })
                card?.startAnimation(fadeOutAnim)
            }, 800)
        } ?: run {
            isOverlayShowing = false
            isHiding = false
        }
    }

    private fun removeOverlay() {
        hideOverlay()
        overlayView = null
    }

    // ---------- Throttled Face Analyzer ----------
    private class FaceAnalyzer(
        initialIntervalMs: Long,
        private val onFacesDetected: (Int) -> Unit
    ) : ImageAnalysis.Analyzer {

        private val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        private val detector = FaceDetection.getClient(options)

        @Volatile
        private var analysisIntervalMs: Long = initialIntervalMs
        private var lastAnalyzedTimestamp = 0L

        fun setInterval(intervalMs: Long) {
            analysisIntervalMs = intervalMs
            Log.d(TAG, "Analysis interval set to ${intervalMs}ms")
        }

        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
            val currentTimestamp = System.currentTimeMillis()

            // Throttle: skip frames if we analyzed too recently
            if (currentTimestamp - lastAnalyzedTimestamp < analysisIntervalMs) {
                imageProxy.close()
                return
            }
            lastAnalyzedTimestamp = currentTimestamp

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        onFacesDetected(faces.size)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Face detection failed", e)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }
}
