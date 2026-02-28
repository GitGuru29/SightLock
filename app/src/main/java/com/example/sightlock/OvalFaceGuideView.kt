package com.example.sightlock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Custom View that draws a semi-transparent overlay with an oval cutout,
 * used to guide the user's face position during registration.
 */
class OvalFaceGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88000000")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Used to "punch out" the oval from the dark overlay
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    var isActive: Boolean = false
        set(value) { field = value; invalidate() }

    var isBlink: Boolean = false
        set(value) { field = value; invalidate() }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Required for PorterDuff clear
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Draw dark overlay
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        // Cut out oval
        val ovalRect = RectF(20f, 20f, w - 20f, h - 20f)
        canvas.drawOval(ovalRect, clearPaint)

        // Draw border
        borderPaint.color = when {
            isBlink -> Color.parseColor("#22C55E")
            isActive -> Color.WHITE
            else -> Color.parseColor("#80FFFFFF")
        }
        borderPaint.strokeWidth = if (isActive || isBlink) 5f else 3f
        canvas.drawOval(ovalRect, borderPaint)
    }
}
