package com.example.sightlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * A highly optimized View that renders a sub-pixel or pixel-level grid pattern.
 * This pattern causes optical interference when viewed from an angle, creating a
 * "privacy screen protector" effect purely in software.
 */
class PrivacyMeshView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val meshPaint = Paint().apply {
        isAntiAlias = false // We want hard pixels for the grid
        isFilterBitmap = false
    }

    // Default configuration
    private var isMeshActive = false
    // A dark, slightly transparent color. 
    // The exact color depends on screen tech (OLED vs LCD), 
    // but semi-transparent black works well universally.
    private val pixelColor = Color.argb(220, 0, 0, 0)
    
    // We optionally use a base dimming layer to reduce overall screen brightness slightly,
    // which significantly enhances the physical privacy effect.
    private val dimPaint = Paint().apply {
        color = Color.argb(40, 0, 0, 0)
        style = Paint.Style.FILL
    }

    init {
        createMeshPattern()
    }

    private fun createMeshPattern() {
        // Create a tiny 2x2 bitmap. We want a checkerboard or a vertical line structure.
        // A vertical line structure (1px wide) often works best for horizontal privacy.
        //
        // Bitmap Structure (2x2):
        // [ Color ] [ Transparent ]
        // [ Color ] [ Transparent ]
        
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, pixelColor)
        bitmap.setPixel(1, 0, Color.TRANSPARENT)
        bitmap.setPixel(0, 1, pixelColor)
        bitmap.setPixel(1, 1, Color.TRANSPARENT)

        // Use a BitmapShader to repeat this tiny bitmap infinitely across the canvas
        // This is incredibly memory efficient and very fast to draw.
        val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        meshPaint.shader = shader
    }

    fun setMeshActive(active: Boolean) {
        if (isMeshActive != active) {
            isMeshActive = active
            invalidate() // Request a redraw
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isMeshActive) {
            // First, slightly dim the entire screen. This reduces contrast 
            // for peeking eyes before the mesh is even applied.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            
            // Then, draw the repeating privacy mesh pattern over the top
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), meshPaint)
        }
    }
}
