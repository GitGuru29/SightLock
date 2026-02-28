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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var captureProgress: ProgressBar
    private lateinit var tvCaptureLabel: TextView

    // Camera
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var latestBitmap: Bitmap? = null

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
        WAITING_FOR_FACE,   // Waiting for user's face to appear in oval
        BLINK_CHALLENGE,    // Face detected, waiting for blink
        CAPTURING,          // Blink confirmed, capturing frames
        DONE
    }

    private var state = RegistrationState.WAITING_FOR_FACE
    private var capturedEmbeddings = mutableListOf<FloatArray>()
    private val REQUIRED_CAPTURES = 5

    // Blink detection state
    private var eyesWereClosed = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register_face)

        cameraPreview = findViewById(R.id.camera_preview)
        faceGuide = findViewById(R.id.face_guide)
        tvInstruction = findViewById(R.id.tv_instruction)
        ivEyeIcon = findViewById(R.id.iv_eye_icon)
        captureProgress = findViewById(R.id.capture_progress)
        tvCaptureLabel = findViewById(R.id.tv_capture_label)

        embeddingEngine = FaceEmbeddingEngine(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

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

        // Capture latest bitmap for embedding extraction
        val bitmap = imageProxy.toBitmap()
        latestBitmap = bitmap

        val mediaImage = imageProxy.image
        if (mediaImage == null) { imageProxy.close(); return }

        val mlkitImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(mlkitImage)
            .addOnSuccessListener { faces ->
                mainHandler.post { processFaces(faces, bitmap) }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun processFaces(faces: List<Face>, bitmap: Bitmap) {
        val face = faces.firstOrNull()

        when (state) {
            RegistrationState.WAITING_FOR_FACE -> {
                if (face != null) {
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

                // Blink detected: eyes were open, then < 0.2, now open again
                if (avgOpen < 0.2f) {
                    eyesWereClosed = true
                } else if (eyesWereClosed && avgOpen > 0.6f) {
                    eyesWereClosed = false
                    transitionToCapturing()
                }
            }

            RegistrationState.CAPTURING -> {
                if (face != null) {
                    val embedding = embeddingEngine.getEmbedding(bitmap, face.boundingBox)
                    if (embedding != null) {
                        capturedEmbeddings.add(embedding)
                        updateCaptureProgress(capturedEmbeddings.size)

                        if (capturedEmbeddings.size >= REQUIRED_CAPTURES) {
                            finishRegistration()
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
        tvInstruction.text = "Position your face in the oval"
        ivEyeIcon.visibility = View.GONE
    }

    private fun transitionToBlink() {
        state = RegistrationState.BLINK_CHALLENGE
        faceGuide.isActive = true
        faceGuide.isBlink = false
        tvInstruction.text = "Now blink slowly to confirm liveness"
        ivEyeIcon.visibility = View.VISIBLE
        // Animate eye icon
        val blink = AlphaAnimation(1f, 0.2f).apply {
            duration = 700; repeatMode = android.view.animation.Animation.REVERSE
            repeatCount = android.view.animation.Animation.INFINITE
        }
        ivEyeIcon.startAnimation(blink)
    }

    private fun transitionToCapturing() {
        state = RegistrationState.CAPTURING
        capturedEmbeddings.clear()
        faceGuide.isBlink = true
        tvInstruction.text = "Hold still..."
        ivEyeIcon.clearAnimation()
        ivEyeIcon.visibility = View.GONE
        captureProgress.progress = 0
        captureProgress.visibility = View.VISIBLE
        tvCaptureLabel.visibility = View.VISIBLE
    }

    private fun updateCaptureProgress(count: Int) {
        captureProgress.progress = count
        tvCaptureLabel.text = "Capturing ${count}/${REQUIRED_CAPTURES}..."
    }

    private fun finishRegistration() {
        state = RegistrationState.DONE
        cameraProvider?.unbindAll()

        val avgEmbedding = embeddingEngine.averageEmbeddings(capturedEmbeddings)
        OwnerFaceStore.saveEmbedding(this, avgEmbedding)

        mainHandler.post {
            captureProgress.progress = REQUIRED_CAPTURES
            tvInstruction.text = "✓ Face registered!"
            tvCaptureLabel.text = "Registration complete"
            Toast.makeText(this, "Owner face registered successfully!", Toast.LENGTH_SHORT).show()

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
    }
}
