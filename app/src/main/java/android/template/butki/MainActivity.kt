package android.template.butki

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var deltaSymbol: DeltaSymbolView
    private var isCompanionActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Transparent Status Bar & Dark Neon Background
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.parseColor("#020205")
        }

        // Programmatic Futuristic Layout to prevent any XML missing ID errors
        val root = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#020205"))
        }

        statusTextView = TextView(this).apply {
            text = "[ SYS: IDLE - TAP TO WAKE ]"
            setTextColor(Color.parseColor("#00F0FF"))
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            id = View.generateViewId()
        }

        val statusParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            topMargin = 120
        }
        root.addView(statusTextView, statusParams)

        deltaSymbol = DeltaSymbolView(this).apply {
            id = View.generateViewId()
            setOnClickListener {
                isCompanionActive = !isCompanionActive
                if (isCompanionActive) {
                    statusTextView.text = "[ SYS: BUTKI-AI ACTIVE ]"
                    deltaSymbol.setColor(Color.parseColor("#00F0FF"))
                    deltaSymbol.startGlow()
                    Toast.makeText(this@Context as? MainActivity ?: context, "Butki-Ai is awake!", Toast.LENGTH_SHORT).show()
                } else {
                    statusTextView.text = "[ SYS: IDLE - TAP TO WAKE ]"
                    deltaSymbol.setColor(Color.parseColor("#444444"))
                    deltaSymbol.stopGlow()
                }
            }
        }

        val symbolParams = RelativeLayout.LayoutParams(600, 600).apply {
            addRule(RelativeLayout.CENTER_IN_PARENT)
        }
        root.addView(deltaSymbol, symbolParams)

        val btnAssistant = TextView(this).apply {
            text = "[ BUTKI-AI NEURAL CORE ]"
            setTextColor(Color.parseColor("#00F0FF"))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(30, 20, 30, 20)
        }

        val btnParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            bottomMargin = 100
        }
        root.addView(btnAssistant, btnParams)

        setContentView(root)
    }
}

// Inline Delta Symbol View to ensure zero missing file dependencies
class DeltaSymbolView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
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

    private var currentColor: Int = Color.parseColor("#444444")
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

    init {
        paint.color = currentColor
        glowPaint.color = currentColor
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
        val radius = (width.coerceAtMost(height) / 3f) + (if (isGlowActive) pulseValue * 12f else 0f)

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
