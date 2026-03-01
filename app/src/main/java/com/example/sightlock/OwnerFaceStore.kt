package com.example.sightlock

import android.content.Context
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles persistent, encrypted storage of the owner's face embedding.
 * The embedding is a float array of dimension 192 (MobileFaceNet output).
 */
object OwnerFaceStore {

    private const val PREFS_NAME = "sightlock_owner_prefs"
    private const val KEY_EMBEDDING = "owner_face_embedding"
    private const val TAG = "OwnerFaceStore"

    fun saveEmbedding(context: Context, embedding: FloatArray) {
        val buffer = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.BIG_ENDIAN)
        embedding.forEach { buffer.putFloat(it) }
        val encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EMBEDDING, encoded)
            .apply()
        Log.d(TAG, "Owner embedding saved (${embedding.size} dims)")
    }

    fun loadEmbedding(context: Context): FloatArray? {
        val encoded = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EMBEDDING, null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            FloatArray(bytes.size / 4) { buffer.float }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load embedding", e)
            null
        }
    }

    fun hasEmbedding(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .contains(KEY_EMBEDDING)

    fun clearEmbedding(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_EMBEDDING)
            .apply()
    }
}
