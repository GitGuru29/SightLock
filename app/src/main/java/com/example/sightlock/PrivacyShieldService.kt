package com.example.sightlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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

    companion object {
        private const val TAG = "PrivacyShieldService"
        private const val NOTIFICATION_CHANNEL_ID = "privacy_shield_channel"
        private const val NOTIFICATION_ID = 1
        
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

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, createNotification())
        startCamera()
        return Service.START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        removeOverlay()
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
            .setSmallIcon(android.R.drawable.ic_secure) // Replace with your app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this@PrivacyShieldService)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, FaceAnalyzer { facesCount ->
                            handleFacesDetected(facesCount)
                        })
                    }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this@PrivacyShieldService, cameraSelector, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this@PrivacyShieldService))
    }

    private fun handleFacesDetected(facesCount: Int) {
        // Must run on main thread to manipulate UI
        ContextCompat.getMainExecutor(this@PrivacyShieldService).execute {
            if (facesCount > 1) { // More than 1 person looking at the screen
                if (!isOverlayShowing) {
                    showOverlay()
                }
            } else {
                if (isOverlayShowing) {
                    hideOverlay()
                }
            }
        }
    }

    private fun createOverlayView() {
        // Inflate a simple layout. A Service is a Context but not a themed UI Context.
        val themedContext = android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        overlayView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_privacy_shield, null).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#E6000000")) // 90% black
            // You can add a text view here saying "Peeking Detected!"
        }
    }

    private fun showOverlay() {
        if (overlayView == null) return
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        try {
            windowManager?.addView(overlayView, params)
            isOverlayShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay: \${e.message}")
        }
    }

    private fun hideOverlay() {
        try {
            if (isOverlayShowing) {
                windowManager?.removeView(overlayView)
                isOverlayShowing = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay: \${e.message}")
        }
    }
    
    private fun removeOverlay() {
        hideOverlay()
        overlayView = null
    }

    private class FaceAnalyzer(private val onFacesDetected: (Int) -> Unit) : ImageAnalysis.Analyzer {
        private val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        private val detector = FaceDetection.getClient(options)

        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
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
