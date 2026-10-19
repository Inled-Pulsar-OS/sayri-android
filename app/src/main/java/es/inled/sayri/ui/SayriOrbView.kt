package es.inled.sayri.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

/**
 * Animated Glowing Sayri Orb Widget.
 * Replicates the Apple-Intelligence / Sayri Cajita iridescent rotating harmonic gradient.
 */
class SayriOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var rotationAngle = 0f
    private var pulseFactor = 1.0f
    private var waveProgress = 0f

    var state: OrbState = OrbState.IDLE
        set(value) {
            field = value
            invalidate()
        }

    // Dynamic gradient palette inspired by Sayri Cajita (#7c166e, #1e74fb, #00f5d4, #9d4edd)
    private val gradientColors = intArrayOf(
        Color.parseColor("#7C166E"), // Magenta/Wine
        Color.parseColor("#1E74FB"), // Electric Blue
        Color.parseColor("#00F5D4"), // Neon Cyan
        Color.parseColor("#9D4EDD"), // Neon Purple
        Color.parseColor("#7C166E")  // Close loop
    )

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0.92f, 1.08f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            pulseFactor = it.animatedValue as Float
            waveProgress = (waveProgress + 0.03f) % 1.0f
            invalidate()
        }
    }

    init {
        rotationAnimator.start()
        pulseAnimator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (minOf(cx, cy) * 0.65f) * pulseFactor

        // 1. Draw outer harmonic glow wave
        val waveRadius = baseRadius * (1.1f + 0.35f * waveProgress)
        val waveAlpha = ((1.0f - waveProgress) * 140).toInt().coerceIn(0, 255)
        wavePaint.color = Color.parseColor("#1E74FB")
        wavePaint.alpha = waveAlpha
        canvas.drawCircle(cx, cy, waveRadius, wavePaint)

        // 2. Draw outer ambient blurred glow
        val glowRadius = baseRadius * 1.25f
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.parseColor("#667C166E"),
                Color.parseColor("#331E74FB"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.4f, 0.75f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // 3. Draw rotating iridescent core orb
        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)

        val sweepShader = SweepGradient(cx, cy, gradientColors, null)
        orbPaint.shader = sweepShader
        canvas.drawCircle(cx, cy, baseRadius, orbPaint)

        // 4. Draw inner lighting highlight reflection
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - baseRadius * 0.35f,
                cy - baseRadius * 0.35f,
                baseRadius * 0.8f,
                Color.argb(160, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, baseRadius, highlightPaint)

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        rotationAnimator.cancel()
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }
}
