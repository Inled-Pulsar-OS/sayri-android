package es.inled.sayri.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

enum class OrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

/**
 * Animated Glowing Sayri Orb Widget.
 * Replicates the exact default UI WebKit Siri Orb and Cajita Chroma-Ring aesthetics.
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
        strokeWidth = 3.5f
    }
    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var rotationAngle = 0f
    private var pulseFactor = 1.0f
    private var waveProgress = 0f
    private var audioReactiveFactor = 0f

    var state: OrbState = OrbState.IDLE
        set(value) {
            field = value
            when (value) {
                OrbState.IDLE -> {
                    rotationAnimator.duration = 7000L
                }
                OrbState.LISTENING -> {
                    rotationAnimator.duration = 4000L
                }
                OrbState.THINKING -> {
                    rotationAnimator.duration = 1800L
                }
                OrbState.SPEAKING -> {
                    rotationAnimator.duration = 3200L
                }
            }
            invalidate()
        }

    // Exact Chroma-Ring palette from Cajita & Siri Orb (#7c166e, #1e74fb, #00f5d4, #9d4edd)
    private val gradientColors = intArrayOf(
        Color.parseColor("#7C166E"), // Magenta/wine
        Color.parseColor("#1E74FB"), // Electric Blue
        Color.parseColor("#00F5D4"), // Neon Cyan
        Color.parseColor("#9D4EDD"), // Neon Purple
        Color.parseColor("#1E74FB"), // Return to Blue
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

    private val pulseAnimator = ValueAnimator.ofFloat(0.94f, 1.06f).apply {
        duration = 1600L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            pulseFactor = it.animatedValue as Float
            waveProgress = (waveProgress + 0.025f) % 1.0f
            invalidate()
        }
    }

    init {
        rotationAnimator.start()
        pulseAnimator.start()
    }

    fun setAudioLevel(rmsdB: Float) {
        val normalized = (rmsdB / 10f).coerceIn(0f, 1.2f)
        audioReactiveFactor = normalized
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val scaleBoost = if (state == OrbState.LISTENING || state == OrbState.SPEAKING) {
            1.0f + 0.15f * audioReactiveFactor
        } else {
            1.0f
        }
        val baseRadius = (minOf(cx, cy) * 0.60f) * pulseFactor * scaleBoost

        // 1. Draw outer harmonic wave ripples (radiating from crests)
        val waveRadius1 = baseRadius * (1.10f + 0.35f * waveProgress)
        val waveAlpha1 = ((1.0f - waveProgress) * 160).toInt().coerceIn(0, 255)
        wavePaint.color = Color.parseColor("#1E74FB")
        wavePaint.alpha = waveAlpha1
        canvas.drawCircle(cx, cy, waveRadius1, wavePaint)

        val waveRadius2 = baseRadius * (1.05f + 0.55f * ((waveProgress + 0.5f) % 1.0f))
        val waveAlpha2 = ((1.0f - ((waveProgress + 0.5f) % 1.0f)) * 120).toInt().coerceIn(0, 255)
        wavePaint.color = Color.parseColor("#7C166E")
        wavePaint.alpha = waveAlpha2
        canvas.drawCircle(cx, cy, waveRadius2, wavePaint)

        // 2. Draw ambient diffuse bloom
        val glowRadius = baseRadius * 1.35f
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.parseColor("#667C166E"),
                Color.parseColor("#441E74FB"),
                Color.parseColor("#1100F5D4"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.3f, 0.65f, 0.88f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // 3. Draw rotating iridescent core orb
        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)

        val sweepShader = SweepGradient(cx, cy, gradientColors, null)
        orbPaint.shader = sweepShader
        canvas.drawCircle(cx, cy, baseRadius, orbPaint)

        // 4. Inner specular reflections & depth
        innerGlowPaint.shader = RadialGradient(
            cx - baseRadius * 0.32f,
            cy - baseRadius * 0.32f,
            baseRadius * 0.85f,
            intArrayOf(
                Color.argb(190, 255, 255, 255),
                Color.argb(70, 0, 245, 212),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.0f, 0.45f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, baseRadius, innerGlowPaint)

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        rotationAnimator.cancel()
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }
}
