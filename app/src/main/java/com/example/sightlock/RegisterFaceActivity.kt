package com.example.sightlock

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.os.VibrationEffect
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RegisterFaceActivity : AppCompatActivity() {

    companion object {
        const val RESULT_REGISTERED = 100

        fun startForResult(context: Context, requestCode: Int) {
            (context as? AppCompatActivity)?.startActivityForResult(
                Intent(context, RegisterFaceActivity::class.java),
                requestCode
            )
        }
    }

    // UI
    private lateinit var cameraPreview: PreviewView
    private lateinit var faceGuide: OvalFaceGuideView
    private lateinit var tvInstruction: TextView
    private lateinit var ivEyeIcon: ImageView
    private lateinit var tvCaptureLabel: TextView

    // Camera
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var latestBitmap: Bitmap? = null

    // Debug Panel
    private lateinit var debugPanel: View
    private lateinit var tvDebugFps: TextView
    private lateinit var tvDebugDist: TextView
    private lateinit var tvDebugYaw: TextView
    private lateinit var tvDebugPitch: TextView
    private lateinit var tvDebugState: TextView

    // FPS Calculation
    private var frameCount = 0
    private var lastFpsTimestamp = 0L
    private var currentFps = 0

    // Face detection (ML Kit)
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

    // Embedding engine (TFLite)
    private lateinit var embeddingEngine: FaceEmbeddingEngine

    // Registration State Machine
    private enum class RegistrationState {
        WAITING_FOR_FACE,
        BLINK_CHALLENGE,
        CAPTURING_FRONT,
        CAPTURING_LEFT,
        CAPTURING_RIGHT,
        CAPTURING_UP,
        CAPTURING_DOWN,
        DONE
    }

    private var state = RegistrationState.WAITING_FOR_FACE
    private var capturedEmbeddings = mutableListOf<FloatArray>()
    // 10 Front, 5 Left, 5 Right, 5 Up, 5 Down
    private val REQUIRED_CAPTURES = 30
    private var capturesForCurrentState = 0
    private var lastCaptureTime = 0L

    // Blink detection state
    private var eyesWereClosed = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register_face)

        cameraPreview = findViewById(R.id.camera_preview)
        val tvTitle = findViewById<TextView>(R.id.tv_title)
        faceGuide = findViewById(R.id.face_guide)
        tvInstruction = findViewById(R.id.tv_instruction)
        ivEyeIcon = findViewById(R.id.iv_eye_icon)
        tvCaptureLabel = findViewById(R.id.tv_capture_label)

        // Debug Views
        debugPanel = findViewById(R.id.debug_panel)
        tvDebugFps = findViewById(R.id.tv_debug_fps)
        tvDebugDist = findViewById(R.id.tv_debug_dist)
        tvDebugYaw = findViewById(R.id.tv_debug_yaw)
        tvDebugPitch = findViewById(R.id.tv_debug_pitch)
        tvDebugState = findViewById(R.id.tv_debug_state)

        // Toggle debug panel on long press of the title
        tvTitle.setOnLongClickListener {
            debugPanel.visibility = if (debugPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }

        embeddingEngine = FaceEmbeddingEngine(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 50)
        } catch (e: Exception) {
            android.util.Log.e("RegisterFace", "Failed to create ToneGenerator")
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }

            cameraProvider?.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (state == RegistrationState.DONE) {
            imageProxy.close()
            return
        }

        // FPS Calculation
        frameCount++
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFpsTimestamp >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFpsTimestamp = currentTime
        }

        // Capture latest bitmap for embedding extraction
        val bitmap = imageProxy.toBitmap()
        latestBitmap = bitmap

        // toBitmap() already handles layout rotation upright
        val mlkitImage = InputImage.fromBitmap(bitmap, 0)
        detector.process(mlkitImage)
            .addOnSuccessListener { faces ->
                if (debugPanel.visibility == View.VISIBLE) {
                    tvDebugFps.text = "FPS: $currentFps"
                    tvDebugState.text = "State: ${state.name} (${capturesForCurrentState}/10|5)"
                }
                mainHandler.post { processFaces(faces, bitmap) }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun processFaces(faces: List<Face>, bitmap: Bitmap) {
        val face = faces.firstOrNull()
        if (face == null && state != RegistrationState.WAITING_FOR_FACE) {
            android.util.Log.d("RegisterFace", "Face lost. Resetting to WAITING_FOR_FACE")
        }
        
        if (face != null && debugPanel.visibility == View.VISIBLE) {
            tvDebugDist.text = "Dist (Width): ${face.boundingBox.width()}"
            tvDebugYaw.text = String.format("Yaw (Y): %.2f", face.headEulerAngleY)
            tvDebugPitch.text = String.format("Pitch (X): %.2f", face.headEulerAngleX)
        }

        when (state) {
            RegistrationState.WAITING_FOR_FACE -> {
                if (face != null) {
                    android.util.Log.d("RegisterFace", "Face detected. Transitioning to BLINK_CHALLENGE")
                    transitionToBlink()
                }
            }

            RegistrationState.BLINK_CHALLENGE -> {
                if (face == null) {
                    // Face left the frame, go back to waiting
                    transitionToWaiting()
                    return
                }

                val leftOpen  = face.leftEyeOpenProbability  ?: 1f
                val rightOpen = face.rightEyeOpenProbability ?: 1f
                val avgOpen = (leftOpen + rightOpen) / 2f

                android.util.Log.d("RegisterFace", "Blink: left=$leftOpen right=$rightOpen avg=$avgOpen closed=$eyesWereClosed")

                // Blink detected: eyes were open, then < 0.2, now open again
                if (avgOpen < 0.2f) {
                    eyesWereClosed = true
                } else if (eyesWereClosed && avgOpen > 0.6f) {
                    eyesWereClosed = false
                    transitionToCapturing()
                }
            }

            RegistrationState.CAPTURING_FRONT,
            RegistrationState.CAPTURING_LEFT,
            RegistrationState.CAPTURING_RIGHT,
            RegistrationState.CAPTURING_UP,
            RegistrationState.CAPTURING_DOWN -> {
                if (face != null) {
                    val isValidPose = checkFacePose(face)
                    
                    if (isValidPose) {
                        val embedding = embeddingEngine.getEmbedding(bitmap, face.boundingBox)
                        if (embedding != null) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastCaptureTime >= 300L) { // Faster capture interval
                                lastCaptureTime = currentTime
                                capturedEmbeddings.add(embedding)
                                capturesForCurrentState++
                                updateCaptureProgress(capturedEmbeddings.size)
                                
                                playCaptureFeedback()
                                advanceCaptureStateIfNeeded()
                            }
                        }
                    }
                }
            }

            RegistrationState.DONE -> { /* no-op */ }
        }
    }

    private fun transitionToWaiting() {
        state = RegistrationState.WAITING_FOR_FACE
        eyesWereClosed = false
        faceGuide.isActive = false
        faceGuide.isBlink = false
        faceGuide.isScanning = false
        tvInstruction.text = "Position your face in the oval"
        ivEyeIcon.visibility = View.GONE
    }

    private fun transitionToBlink() {
        state = RegistrationState.BLINK_CHALLENGE
        faceGuide.isActive = true
        faceGuide.isBlink = false
        faceGuide.isScanning = false
        tvInstruction.text = "Now blink slowly to confirm"
        ivEyeIcon.visibility = View.VISIBLE
        // Animate eye icon
        val blink = AlphaAnimation(1f, 0.2f).apply {
            duration = 700; repeatMode = android.view.animation.Animation.REVERSE
            repeatCount = android.view.animation.Animation.INFINITE
        }
        ivEyeIcon.startAnimation(blink)
    }

    private fun transitionToCapturing() {
        state = RegistrationState.CAPTURING_FRONT
        capturedEmbeddings.clear()
        capturesForCurrentState = 0
        faceGuide.isBlink = false
        faceGuide.isScanning = true
        tvInstruction.text = "Look straight ahead"
        ivEyeIcon.clearAnimation()
        ivEyeIcon.visibility = View.GONE
        faceGuide.capturesCompleted = 0
        tvCaptureLabel.visibility = View.VISIBLE
        playHapticPulse()
    }

    private fun playCaptureFeedback() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 30)
    }

    private fun playHapticPulse() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun checkFacePose(face: Face): Boolean {
        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX

        // Warn if face is too far or too close (Bounding box width relative to 480x640 preview)
        val faceWidth = face.boundingBox.width()
        if (faceWidth < 150) tvCaptureLabel.text = "Move closer..."
        else if (faceWidth > 350) tvCaptureLabel.text = "Move further back..."
        else tvCaptureLabel.text = "Hold still..."

        val tolerance = 8f

        return when (state) {
            RegistrationState.CAPTURING_FRONT -> {
                if (Math.abs(yaw) < tolerance && Math.abs(pitch) < tolerance) true
                else { tvInstruction.text = "Look straight ahead"; false }
            }
            RegistrationState.CAPTURING_LEFT -> {
                if (yaw > 15f) true
                else { tvInstruction.text = "Turn head slightly to your LEFT"; false }
            }
            RegistrationState.CAPTURING_RIGHT -> {
                if (yaw < -15f) true
                else { tvInstruction.text = "Turn head slightly to your RIGHT"; false }
            }
            RegistrationState.CAPTURING_UP -> {
                // Pitch is positive when looking up
                if (pitch > 10f) true
                else { tvInstruction.text = "Tilt head slightly UPWARD"; false }
            }
            RegistrationState.CAPTURING_DOWN -> {
                // Pitch is negative when looking down
                if (pitch < -10f) true
                else { tvInstruction.text = "Tilt head slightly DOWNWARD"; false }
            }
            else -> false
        }
    }

    private fun advanceCaptureStateIfNeeded() {
        when (state) {
            RegistrationState.CAPTURING_FRONT -> {
                if (capturesForCurrentState >= 10) {
                    state = RegistrationState.CAPTURING_LEFT
                    capturesForCurrentState = 0
                }
            }
            RegistrationState.CAPTURING_LEFT -> {
                if (capturesForCurrentState >= 5) {
                    state = RegistrationState.CAPTURING_RIGHT
                    capturesForCurrentState = 0
                }
            }
            RegistrationState.CAPTURING_RIGHT -> {
                if (capturesForCurrentState >= 5) {
                    state = RegistrationState.CAPTURING_UP
                    capturesForCurrentState = 0
                }
            }
            RegistrationState.CAPTURING_UP -> {
                if (capturesForCurrentState >= 5) {
                    state = RegistrationState.CAPTURING_DOWN
                    capturesForCurrentState = 0
                }
            }
            RegistrationState.CAPTURING_DOWN -> {
                if (capturesForCurrentState >= 5) {
                    finishRegistration()
                }
            }
            else -> {}
        }
    }

    private fun updateCaptureProgress(count: Int) {
        faceGuide.capturesCompleted = count
        // Label is now handled by checkFacePose warning about distance
    }

    private fun finishRegistration() {
        state = RegistrationState.DONE
        cameraProvider?.unbindAll()

        val avgEmbedding = embeddingEngine.averageEmbeddings(capturedEmbeddings)
        OwnerFaceStore.saveEmbedding(this, avgEmbedding)

        mainHandler.post {
            playHapticPulse()
            faceGuide.capturesCompleted = REQUIRED_CAPTURES
            faceGuide.isScanning = false
            faceGuide.isBlink = true // Turn it solid green
            tvInstruction.text = "Face registered"
            tvCaptureLabel.text = "Setup complete"
            Toast.makeText(this, "Master profile saved successfully", Toast.LENGTH_SHORT).show()

            mainHandler.postDelayed({
                setResult(RESULT_REGISTERED)
                finish()
            }, 1200)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        embeddingEngine.close()
        toneGenerator?.release()
    }
}
