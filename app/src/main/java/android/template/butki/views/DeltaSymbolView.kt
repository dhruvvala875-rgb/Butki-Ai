package android.template.butki.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class DeltaSymbolView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 16f
        maskFilter = android.graphics.BlurMaskFilter(20f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    private var currentColor: Int = android.graphics.Color.parseColor("#00F0FF")
    private var isGlowActive = false
    private var pulseValue = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulseValue = it.animatedValue as Float
            invalidate()
        }
    }

    fun setColor(color: Int) {
        currentColor = color
        paint.color = color
        glowPaint.color = color
        invalidate()
    }

    fun startGlow() {
        isGlowActive = true
        if (!animator.isRunning) {
            animator.start()
        }
    }

    fun stopGlow() {
        isGlowActive = false
        animator.cancel()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (width.coerceAtMost(height) / 3f) + (if (isGlowActive) pulseValue * 10f else 0f)

        if (isGlowActive) {
            canvas.drawCircle(cx, cy, radius, glowPaint)
        }
        canvas.drawCircle(cx, cy, radius, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
