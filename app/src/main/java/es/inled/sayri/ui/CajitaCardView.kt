package es.inled.sayri.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.sin

/**
 * CajitaCardView replicates the exact Apple-Intelligence / Sayri Desktop Cajita
 * frosted acrylic card with the animated Chroma-Ring border and localized wave bloom
 * using palette #7C166E (Magenta), #1E74FB (Electric Blue), and #0B1533 (Midnight Navy).
 */
class CajitaCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F00D1424") // rgba(13, 20, 36, 0.94)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }

    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.0f
    }

    private val cornerRadius = 24f * resources.displayMetrics.density
    private val rectF = RectF()
    private val clipPath = Path()

    var mode: String = "idle" // "idle", "active", "thinking", "speaking"
        set(value) {
            field = value
            if (value in listOf("active", "thinking", "speaking")) {
                if (!animator.isRunning) animator.start()
            } else {
                animator.cancel()
            }
            invalidate()
        }

    private var angle: Float = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 5000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = 3f * resources.displayMetrics.density
        rectF.set(pad, pad, w - pad, h - pad)
        clipPath.reset()
        clipPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(canvas)
            return
        }

        // 1. Draw frosted acrylic glass background
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)

        // 2. Render children inside rounded bounds
        canvas.save()
        canvas.clipPath(clipPath)
        super.dispatchDraw(canvas)
        canvas.restore()

        // 3. Draw Chroma-Ring animated border
        if (mode in listOf("active", "thinking", "speaking")) {
            val rad = Math.toRadians(angle.toDouble()).toFloat()
            val cosA = cos(rad)
            val sinA = sin(rad)
            val span = (maxOf(w, h) / 1.5f)

            val x0 = w / 2f + cosA * span
            val y0 = h / 2f + sinA * span
            val x1 = w / 2f - cosA * span
            val y1 = h / 2f - sinA * span

            // Gradient stops matching cajita.py: #7c166e -> #1e74fb -> #7c166e -> #0b1533
            val colors = intArrayOf(
                Color.parseColor("#7C166E"),
                Color.parseColor("#1E74FB"),
                Color.parseColor("#7C166E"),
                Color.parseColor("#0B1533")
            )
            val positions = floatArrayOf(0.0f, 0.40f, 0.75f, 1.0f)
            val chromaShader = LinearGradient(x0, y0, x1, y1, colors, positions, Shader.TileMode.CLAMP)

            // Localized wave bloom
            bloomPaint.shader = chromaShader
            bloomPaint.alpha = 110
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bloomPaint)

            // Core border
            borderPaint.shader = chromaShader
            borderPaint.alpha = 240
            borderPaint.strokeWidth = 2.2f * resources.displayMetrics.density
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)
        } else {
            // Idle subtle glass border
            borderPaint.shader = null
            borderPaint.color = Color.parseColor("#26FFFFFF") // 15% white glass border
            borderPaint.strokeWidth = 1.0f * resources.displayMetrics.density
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)
        }
    }
}
