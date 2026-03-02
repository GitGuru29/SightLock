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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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

    // Face embedding engine for owner recognition
    private lateinit var embeddingEngine: FaceEmbeddingEngine
    private var ownerEmbedding: FloatArray? = null

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

        // Load owner face embedding
        embeddingEngine = FaceEmbeddingEngine(this)
        ownerEmbedding = OwnerFaceStore.loadEmbedding(this)
        Log.d(TAG, "Owner embedding loaded: ${ownerEmbedding != null}")

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
        embeddingEngine.close()
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
        faceAnalyzer?.resetLiveness()
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

                faceAnalyzer = FaceAnalyzer(
                    initialIntervalMs = NORMAL_ANALYSIS_INTERVAL_MS,
                    ownerEmbedding = ownerEmbedding,
                    embeddingEngine = embeddingEngine
                ) { isThreat ->
                    handleThreatDetected(isThreat)
                }

                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
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

    private fun handleThreatDetected(isThreat: Boolean) {
        ContextCompat.getMainExecutor(this@PrivacyShieldService).execute {
            if (isThreat) {
                if (!isOverlayShowing) {
                    triggerWarningVibration()
                    showOverlay()
                    faceAnalyzer?.setInterval(OVERLAY_ANALYSIS_INTERVAL_MS)
                }
            } else {
                if (isOverlayShowing) {
                    hideOverlay()
                    faceAnalyzer?.setInterval(NORMAL_ANALYSIS_INTERVAL_MS)
                }
            }
        }
    }

    private fun triggerWarningVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Two quick pulses
                val timings = longArrayOf(0, 150, 100, 150)
                val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 150, 100, 150), -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate", e)
        }
    }

    private fun createOverlayView() {
        val themedContext = android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        overlayView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_privacy_shield, null)
    }

    private fun showOverlay() {
        if (overlayView == null) createOverlayView()
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

                card?.startAnimation(fadeOutAnim)
                
                // Reliably remove view and force re-inflation next time
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        windowManager?.removeView(overlayView)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing overlay: ${e.message}")
                    }
                    isOverlayShowing = false
                    isHiding = false
                    overlayView = null
                }, 300)
            }, 800)
        } ?: run {
            isOverlayShowing = false
            isHiding = false
            overlayView = null
        }
    }

    private fun removeOverlay() {
        hideOverlay()
        // If the service is destroyed immediately, ensure we don't leak
        try {
            if (isOverlayShowing) {
                windowManager?.removeView(overlayView)
            }
        } catch (e: Exception) {}
        overlayView = null
    }

    // ---------- Liveness-Aware Face Analyzer with Owner Recognition ----------
    private class FaceAnalyzer(
        initialIntervalMs: Long,
        private val ownerEmbedding: FloatArray?,
        private val embeddingEngine: FaceEmbeddingEngine,
        private val onThreatChanged: (Boolean) -> Unit
    ) : ImageAnalysis.Analyzer {

        private val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        private val detector = FaceDetection.getClient(options)

        @Volatile
        private var analysisIntervalMs: Long = initialIntervalMs
        private var lastAnalyzedTimestamp = 0L

        // Liveness tracking
        data class FaceLivenessData(
            val eulerX: Float,
            val eulerY: Float,
            val eulerZ: Float,
            val leftEye: Float,
            val rightEye: Float
        )
        private val faceHistory = mutableListOf<FaceLivenessData>()
        private var threatConfirmCount = 0
        private var clearCount = 0

        companion object {
            // Number of past frames to remember per face (using approx 2.5 seconds at 2 FPS)
            private const val HISTORY_FRAMES = 5
            // Consecutive detections of movement required before reporting threat
            private const val CONFIRM_FRAMES = 3
            // Consecutive "no threat" frames required before reporting clear
            private const val CLEAR_FRAMES = 2
        }

        fun setInterval(intervalMs: Long) {
            analysisIntervalMs = intervalMs
            Log.d(TAG, "Analysis interval set to ${intervalMs}ms")
        }

        fun resetLiveness() {
            faceHistory.clear()
            threatConfirmCount = 0
            clearCount = 0
        }

        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
            val now = System.currentTimeMillis()
            if (now - lastAnalyzedTimestamp < analysisIntervalMs) { imageProxy.close(); return }
            lastAnalyzedTimestamp = now

            // Capture bitmap for face crop/analysis
            val bitmap = imageProxy.toBitmap()

            // toBitmap() already handles layout rotation upright
            val mlImage = InputImage.fromBitmap(bitmap, 0)
            detector.process(mlImage)
                .addOnSuccessListener { faces -> evaluateThreats(faces, bitmap) }
                .addOnFailureListener { e -> Log.e(TAG, "Face detection failed", e) }
                .addOnCompleteListener { imageProxy.close() }
        }

        private fun evaluateThreats(
            faces: List<com.google.mlkit.vision.face.Face>,
            bitmap: android.graphics.Bitmap
        ) {
            if (faces.isEmpty()) {
                faceHistory.clear()
                threatConfirmCount = 0
                clearCount++
                if (clearCount >= CLEAR_FRAMES) onThreatChanged(false)
                return
            }

            clearCount = 0

            // Liveness: evaluate 3D rotation and eye blinking instead of 2D translation
            val avgEulerX = faces.map { it.headEulerAngleX }.average().toFloat()
            val avgEulerY = faces.map { it.headEulerAngleY }.average().toFloat()
            val avgEulerZ = faces.map { it.headEulerAngleZ }.average().toFloat()
            val avgLeftEye = faces.map { it.leftEyeOpenProbability ?: 0.5f }.average().toFloat()
            val avgRightEye = faces.map { it.rightEyeOpenProbability ?: 0.5f }.average().toFloat()

            val livenessData = FaceLivenessData(avgEulerX, avgEulerY, avgEulerZ, avgLeftEye, avgRightEye)
            faceHistory.add(livenessData)
            if (faceHistory.size > HISTORY_FRAMES) faceHistory.removeAt(0)

            if (!isLiveFace()) {
                Log.d(TAG, "No 3D/Eye movement — likely a photo, ignoring.")
                threatConfirmCount = 0
                return
            }

            // If no owner embedding, shield is disabled
            val ownerEmb = ownerEmbedding ?: run {
                Log.w(TAG, "No owner embedding stored — threat detection disabled.")
                onThreatChanged(false)
                return
            }

            // Recognition: any face not matching the owner is a threat
            var unknownFaceFound = false
            for (face in faces) {
                val faceEmb = embeddingEngine.getEmbedding(bitmap, face.boundingBox)
                if (faceEmb != null) {
                    val similarity = FaceEmbeddingEngine.cosineSimilarity(ownerEmb, faceEmb)
                    Log.d(TAG, "Face similarity to owner: ${"%.3f".format(similarity)}")
                    if (similarity < FaceEmbeddingEngine.SIMILARITY_THRESHOLD) {
                        unknownFaceFound = true
                        break
                    }
                }
            }

            if (unknownFaceFound) {
                threatConfirmCount++
                if (threatConfirmCount >= CONFIRM_FRAMES) onThreatChanged(true)
            } else {
                threatConfirmCount = 0
                clearCount++
                if (clearCount >= CLEAR_FRAMES) onThreatChanged(false)
            }
        }

        private fun isLiveFace(): Boolean {
            if (faceHistory.size < 2) return false
            var maxEulerX = -999f
            var minEulerX = 999f
            var maxEulerY = -999f
            var minEulerY = 999f
            var maxEye = -999f
            var minEye = 999f

            for (data in faceHistory) {
                if (data.eulerX > maxEulerX) maxEulerX = data.eulerX
                if (data.eulerX < minEulerX) minEulerX = data.eulerX
                if (data.eulerY > maxEulerY) maxEulerY = data.eulerY
                if (data.eulerY < minEulerY) minEulerY = data.eulerY
                
                val avgEye = (data.leftEye + data.rightEye) / 2f
                if (avgEye > maxEye) maxEye = avgEye
                if (avgEye < minEye) minEye = avgEye
            }

            val eulerXVar = maxEulerX - minEulerX
            val eulerYVar = maxEulerY - minEulerY
            val eyeVar = maxEye - minEye

            // A live face will exhibit at least one of these over 5 frames (2.5 seconds):
            // 1. Natural 3D head turning/pitching (EulerX or EulerY change > 1.0 degrees)
            // 2. Eye blinking/flutter (Eye open probability change > 0.05)
            return (eulerXVar > 1.0f || eulerYVar > 1.0f || eyeVar > 0.05f)
        }
    }
}
