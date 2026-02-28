package com.example.sightlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * On-device face embedding engine using MobileFaceNet TFLite.
 * Input:  112x112 RGB face crop, normalized to [-1, 1]
 * Output: 128-dimensional embedding vector (L2-normalized for cosine comparison)
 */
class FaceEmbeddingEngine(context: Context) {

    companion object {
        private const val TAG = "FaceEmbeddingEngine"
        private const val MODEL_FILE = "mobile_face_net.tflite"
        private const val INPUT_SIZE = 112
        private const val EMBEDDING_SIZE = 128

        // Owner and unknown faces are "same person" if cosine similarity > this threshold
        const val SIMILARITY_THRESHOLD = 0.65f

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f; var normA = 0f; var normB = 0f
            for (i in a.indices) {
                dot  += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            return if (normA == 0f || normB == 0f) 0f
            else dot / (sqrt(normA.toDouble()) * sqrt(normB.toDouble())).toFloat()
        }
    }

    private val interpreter: Interpreter? by lazy {
        try {
            val model = loadModelFile(context)
            Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
            null
        }
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength
        )
    }

    /**
     * Crops a face from the full camera bitmap using the ML Kit bounding box,
     * then runs MobileFaceNet to get the embedding.
     */
    fun getEmbedding(fullBitmap: Bitmap, boundingBox: Rect): FloatArray? {
        return try {
            // Clamp bounding box to bitmap bounds
            val left   = boundingBox.left.coerceIn(0, fullBitmap.width)
            val top    = boundingBox.top.coerceIn(0, fullBitmap.height)
            val right  = boundingBox.right.coerceIn(0, fullBitmap.width)
            val bottom = boundingBox.bottom.coerceIn(0, fullBitmap.height)

            if (right <= left || bottom <= top) return null

            val faceCrop = Bitmap.createBitmap(fullBitmap, left, top, right - left, bottom - top)
            val resized = Bitmap.createScaledBitmap(faceCrop, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuffer = preprocessBitmap(resized)

            val outputArray = Array(1) { FloatArray(EMBEDDING_SIZE) }
            interpreter?.run(inputBuffer, outputArray)

            val embedding = outputArray[0]
            l2Normalize(embedding)
            embedding
        } catch (e: Exception) {
            Log.e(TAG, "Embedding extraction failed", e)
            null
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF).toFloat()
            val g = ((pixel shr 8)  and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()
            // Normalize to [-1, 1]
            buffer.putFloat((r - 128f) / 128f)
            buffer.putFloat((g - 128f) / 128f)
            buffer.putFloat((b - 128f) / 128f)
        }
        return buffer
    }

    private fun l2Normalize(v: FloatArray) {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }.toFloat())
        if (norm > 0) for (i in v.indices) v[i] /= norm
    }

    /**
     * Average multiple embeddings into one representative embedding.
     */
    fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        val avg = FloatArray(EMBEDDING_SIZE)
        embeddings.forEach { emb -> for (i in avg.indices) avg[i] += emb[i] }
        val n = embeddings.size.toFloat()
        for (i in avg.indices) avg[i] /= n
        l2Normalize(avg)
        return avg
    }

    fun close() {
        interpreter?.close()
    }
}
