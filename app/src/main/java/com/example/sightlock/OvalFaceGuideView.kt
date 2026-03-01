package com.example.sightlock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Matrix
import android.os.SystemClock
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

    // Animation progress (0.0 to 1.0)
    var scanProgress: Float = 0f
        set(value) { field = value; invalidate() }

    // Number of captures completed for circular progress (0 to maxCaptures)
    var capturesCompleted: Int = 0
        set(value) { field = value; invalidate() }
        
    var maxCaptures: Int = 30
        set(value) { field = value; invalidate() }

    private var scanLineY: Float = 0f
    private var scanLineDirection: Int = 1
    private var lastDrawTime: Long = 0

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Softened dark background (90% black) so it's not a claustrophobic pitch black
        color = Color.parseColor("#E6000000")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40007AFF")
        style = Paint.Style.STROKE
        strokeWidth = 15f
        maskFilter = android.graphics.BlurMaskFilter(20f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34C759") // iOS Green
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Used to "punch out" the oval from the dark overlay
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    var isActive: Boolean = false
        set(value) { field = value; invalidate() }

    var isBlink: Boolean = false
        set(value) { field = value; invalidate() }

    var isScanning: Boolean = false
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

        // Cut out oval - fixed size centered in the view
        val rectWidth = 280f * context.resources.displayMetrics.density
        val rectHeight = 340f * context.resources.displayMetrics.density
        
        val left = (w - rectWidth) / 2f
        val top = (h - rectHeight) * 0.35f // Slightly offset towards top
        
        val ovalRect = RectF(left, top, left + rectWidth, top + rectHeight)
        canvas.drawOval(ovalRect, clearPaint)

        // Determine Theme Color
        val themeColor = when {
            isBlink -> Color.parseColor("#34C759") // iOS Style Green
            isActive || isScanning -> Color.parseColor("#007AFF") // iOS Style Blue
            else -> Color.parseColor("#80FFFFFF") // Dim white waiting
        }

        borderPaint.color = themeColor
        borderPaint.strokeWidth = if (isActive || isScanning || isBlink) 6f else 4f
        
        // Draw Glow
        if (isActive || isScanning || isBlink) {
            val pulseAlpha = (Math.sin(SystemClock.elapsedRealtime() / 300.0) * 100 + 155).toInt()
            glowPaint.color = Color.argb(pulseAlpha, Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor))
            canvas.drawOval(ovalRect, glowPaint)
        }

        // Draw Base Border
        canvas.drawOval(ovalRect, borderPaint)

        // Draw Circular Progress
        if (maxCaptures > 0 && capturesCompleted > 0) {
            val sweepAngle = 360f * (capturesCompleted.toFloat() / maxCaptures)
            canvas.drawArc(ovalRect, -90f, sweepAngle, false, progressPaint)
        }

        // Draw Scanning Line
        if (isScanning) {
            val currentTime = SystemClock.elapsedRealtime()
            if (lastDrawTime == 0L) lastDrawTime = currentTime
            val deltaMs = currentTime - lastDrawTime
            lastDrawTime = currentTime

            val scanSpeed = h * 0.8f // Pixels per second
            scanLineY += (scanSpeed * (deltaMs / 1000f)) * scanLineDirection

            if (scanLineY > top + rectHeight + 20f) {
                scanLineY = top + rectHeight + 20f
                scanLineDirection = -1
            } else if (scanLineY < top - 20f) {
                scanLineY = top - 20f
                scanLineDirection = 1
            }

            val lineShader = android.graphics.LinearGradient(
                0f, scanLineY - 30f, 0f, scanLineY + 30f,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#80007AFF"), Color.TRANSPARENT),
                null, android.graphics.Shader.TileMode.CLAMP
            )
            scanLinePaint.shader = lineShader

            // Create a mask so the scan line only draws INSIDE the oval
            canvas.save()
            val clipPath = android.graphics.Path()
            clipPath.addOval(ovalRect, android.graphics.Path.Direction.CW)
            canvas.clipPath(clipPath)
            
            canvas.drawRect(0f, scanLineY - 30f, w, scanLineY + 30f, scanLinePaint)
            
            canvas.restore()
            
            // Keep animating
            invalidate()
        } else {
            lastDrawTime = 0L
        }
    }
}
