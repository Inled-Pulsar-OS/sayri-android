package es.inled.sayri.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class OrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

/**
 * SayriOrbView: 1:1 pixel-perfect replica of the Desktop Edition Sayri Orb.
 *
 * Uses the exact color configurations from web/App.js:
 * - primaryColor: #4834cc (Azul oscuro / eléctrico: r=0.282, g=0.204, b=0.800)
 * - secondaryColor: #7f1458 (Rosa / Morado: r=0.498, g=0.078, b=0.345)
 * - dark background: #0e1734 (Negro azulado: r=0.055, g=0.090, b=0.204)
 */
class SayriOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    data class VisualConfig(
        val speed: Float,
        val glowIntensity: Float,
        val coreIntensity: Float,
        val brightness: Float,
        val primaryR: Float,
        val primaryG: Float,
        val primaryB: Float,
        val secondaryR: Float,
        val secondaryG: Float,
        val secondaryB: Float
    )

    // Exact VISUALS configuration from desktop web/App.js
    private val visuals = mapOf(
        OrbState.IDLE to VisualConfig(
            speed = 0.65f,
            glowIntensity = 1.4f,
            coreIntensity = 0.55f,
            brightness = 0.95f,
            primaryR = 0.282f, primaryG = 0.204f, primaryB = 0.800f, // #4834cc
            secondaryR = 0.498f, secondaryG = 0.078f, secondaryB = 0.345f // #7f1458
        ),
        OrbState.LISTENING to VisualConfig(
            speed = 2.0f,
            glowIntensity = 2.2f,
            coreIntensity = 0.70f,
            brightness = 1.10f,
            primaryR = 0.320f, primaryG = 0.240f, primaryB = 0.900f, // #4834cc vibrante
            secondaryR = 0.580f, secondaryG = 0.100f, secondaryB = 0.420f // #7f1458 vibrante
        ),
        OrbState.THINKING to VisualConfig(
            speed = 3.2f,
            glowIntensity = 2.6f,
            coreIntensity = 0.85f,
            brightness = 1.20f,
            primaryR = 0.498f, primaryG = 0.078f, primaryB = 0.345f, // #7f1458 (Rosa/Morado)
            secondaryR = 0.282f, secondaryG = 0.204f, secondaryB = 0.800f // #4834cc (Azul)
        ),
        OrbState.SPEAKING to VisualConfig(
            speed = 1.6f,
            glowIntensity = 2.3f,
            coreIntensity = 0.75f,
            brightness = 1.15f,
            primaryR = 0.282f, primaryG = 0.204f, primaryB = 0.800f, // #4834cc
            secondaryR = 0.498f, secondaryG = 0.078f, secondaryB = 0.345f // #7f1458
        )
    )

    var state: OrbState = OrbState.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private var audioLevel: Float = 0f
    private var smoothedAudioLevel: Float = 0f
    private var phase: Float = 0f
    private var lastTimeNanos: Long = System.nanoTime()

    private var runtimeShader: Any? = null
    private var hasShaderFailed: Boolean = false

    companion object {
        private const val AGSL_SHADER = """
            uniform vec2 iResolution;
            uniform float iTime;
            uniform vec3 primaryColor;
            uniform vec3 secondaryColor;
            uniform float noiseIntensity;
            uniform float glowIntensity;
            uniform float saturation;
            uniform float brightness;
            uniform float rotationSpeed;
            uniform float noiseScale;
            uniform float coreIntensity;
            uniform float edgeSoftness;

            const float TAU = 6.28318530718;

            float rand(vec2 n) { 
              return fract(sin(dot(n, vec2(12.9898, 4.1414))) * 43758.5453);
            }

            float noise(vec2 p) {
              vec2 ip = floor(p);
              vec2 u = fract(p);
              u = u * u * (3.0 - 2.0 * u);
              float res = mix(
                mix(rand(ip), rand(ip + vec2(1.0, 0.0)), u.x),
                mix(rand(ip + vec2(0.0, 1.0)), rand(ip + vec2(1.0, 1.0)), u.x),
                u.y
              );
              return res * res;
            }

            float fbm(vec2 p, int octaves) {
              float s = 0.0;
              float m = 0.0;
              float a = 0.5;
              for (int i = 0; i < 4; i++) {
                if (i >= octaves) break;
                s += a * noise(p);
                m += a;
                a *= 0.5;
                p *= 2.0;
              }
              return s / m;
            }

            float luma(vec3 color) {
              return dot(color, vec3(0.299, 0.587, 0.114));
            }

            vec4 main(vec2 fragCoord) {
              float min_res = min(iResolution.x, iResolution.y);
              vec2 uv = (fragCoord * 2.0 - iResolution.xy) / min_res * 1.5;
              float t = iTime;

              float l = dot(uv, uv);
              
              float edgeOuter = 1.0 + edgeSoftness;
              float edgeInner = 1.0 - edgeSoftness;
              float sm = smoothstep(edgeOuter, edgeInner, l);
              
              if (sm <= 0.0) {
                return vec4(0.0, 0.0, 0.0, 0.0);
              }
              float d = sm * l * l * l * 2.0;
              vec3 norm = normalize(vec3(uv.x, uv.y, 0.7 - d));
              
              float nx = fbm(uv * 2.0 * noiseIntensity + t * 0.4 + 25.69, 4);
              float ny = fbm(uv * 2.0 * noiseIntensity + t * 0.4 + 86.31, 4);
              float n = fbm(uv * noiseScale + 2.0 * vec2(nx, ny), 3);
              
              float a = atan(uv.y, uv.x) / TAU + t * 0.1 * rotationSpeed;
              
              // Exact Desktop Sayri brand colors: #0e1734 (negro azulado)
              vec3 dark_bg = vec3(14.0 / 255.0, 23.0 / 255.0, 52.0 / 255.0);
              
              float blend = 0.5 + 0.5 * sin(a * TAU * 2.0 + (nx - ny) * 2.5 + t * 0.8);
              vec3 fluid_col = mix(primaryColor, secondaryColor, blend);
              
              vec3 col = mix(dark_bg, fluid_col, smoothstep(0.15, 0.85, n));
              col *= saturation;
              
              vec3 cd = abs(col);
              vec3 c = col * d;
              
              c += (c * 0.5 + primaryColor * 0.6 + secondaryColor * 0.4 - luma(c) * 0.5) * vec3(max(0.0, pow(dot(norm, vec3(0.0, 0.0, -1.0)), 5.0) * 3.0));
              
              float g = glowIntensity * smoothstep(0.6, 1.0, fbm(norm.xy * 3.0 / (1.0 + norm.z), 2)) * d;
              c += mix(primaryColor, secondaryColor, blend) * g * 1.5;
              
              col = c + mix(primaryColor, secondaryColor, 0.5) * pow((1.0 - smoothstep(1.0, 0.98, l) - pow(max(0.0, length(uv) - 1.0), 0.2)) * 2.0, 4.0);
              
              float f = fbm(normalize(uv) * 2.0 + t, 2) + 0.1;
              uv *= f + 0.1;
              uv *= 0.5;
              l = dot(uv, uv);
              
              vec3 ins = normalize(cd) + 0.1;
              float ind = 0.2 + pow(smoothstep(0.0, 1.5, sqrt(l)) * 48.0, 0.25);
              ind *= ind * ind * ind;
              ind = 1.0 / ind;
              ins *= ind;
              
              vec3 core_col = mix(primaryColor * 1.2, vec3(0.9, 0.85, 1.0), 0.5);
              col += core_col * ins * ins * sm * smoothstep(0.7, 1.0, ind) * coreIntensity * 2.0;
              col += abs(norm) * (1.0 - d) * sm * 0.25 * dark_bg;
              
              col *= brightness;
              
              return vec4(col, sm);
            }
        """
    }

    private val tickerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = System.nanoTime()
            val dt = ((now - lastTimeNanos) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
            lastTimeNanos = now

            // Smooth audio attack and decay
            val targetLevel = audioLevel
            val lerp = if (targetLevel > smoothedAudioLevel) 0.35f else 0.12f
            smoothedAudioLevel += (targetLevel - smoothedAudioLevel) * lerp

            // Only animate actively when speaking, listening, thinking, or receiving voice audio
            if (state != OrbState.IDLE || smoothedAudioLevel > 0.02f) {
                val cfg = visuals[state] ?: visuals[OrbState.IDLE]!!
                val speed = cfg.speed + smoothedAudioLevel * 3.5f
                phase = (phase + dt * speed) % 10000.0f
                invalidate()
            }
        }
    }

    init {
        initShader()
        tickerAnimator.start()
    }

    private fun initShader() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                runtimeShader = android.graphics.RuntimeShader(AGSL_SHADER)
            } catch (e: Throwable) {
                hasShaderFailed = true
            }
        }
    }

    fun setAudioLevel(rmsdB: Float) {
        audioLevel = (rmsdB / 10f).coerceIn(0f, 1.2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        if (!hasShaderFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader != null) {
            drawAgslShader(canvas, w, h)
        } else {
            drawNativeCairoBlobs(canvas, w, h)
        }
    }

    private fun drawAgslShader(canvas: Canvas, w: Float, h: Float) {
        val shader = runtimeShader as? android.graphics.RuntimeShader ?: return
        val cfg = visuals[state] ?: visuals[OrbState.IDLE]!!

        val dynamicGlow = cfg.glowIntensity * (1.0f + smoothedAudioLevel * 0.8f) + smoothedAudioLevel * 0.9f
        val dynamicCore = cfg.coreIntensity * (1.0f + smoothedAudioLevel * 0.75f) + smoothedAudioLevel * 0.4f
        val dynamicBrightness = cfg.brightness * (1.0f + smoothedAudioLevel * 0.35f)

        shader.setFloatUniform("iResolution", w, h)
        shader.setFloatUniform("iTime", phase)
        shader.setFloatUniform("primaryColor", cfg.primaryR, cfg.primaryG, cfg.primaryB)
        shader.setFloatUniform("secondaryColor", cfg.secondaryR, cfg.secondaryG, cfg.secondaryB)
        shader.setFloatUniform("noiseIntensity", 1.0f + smoothedAudioLevel * 0.6f)
        shader.setFloatUniform("glowIntensity", dynamicGlow)
        shader.setFloatUniform("saturation", 2.0f)
        shader.setFloatUniform("brightness", dynamicBrightness)
        shader.setFloatUniform("rotationSpeed", 1.0f + smoothedAudioLevel * 1.5f)
        shader.setFloatUniform("noiseScale", 3.0f)
        shader.setFloatUniform("coreIntensity", dynamicCore)
        shader.setFloatUniform("edgeSoftness", 0.04f)

        shaderPaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, shaderPaint)
    }

    /**
     * Exact 4-layer organic additive blob Cairo algorithm with Desktop Sayri brand colors.
     */
    private fun drawNativeCairoBlobs(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val maxR = min(w, h) / 2f
        val rBase = maxR * (0.62f + smoothedAudioLevel * 0.16f)
        val cfg = visuals[state] ?: visuals[OrbState.IDLE]!!
        val t = phase

        // 1. Ambient outer glow with desktop palette
        val glowR = min(maxR * 0.95f, rBase * (1.16f + 0.20f * smoothedAudioLevel))
        val outerGlow = RadialGradient(
            cx, cy, glowR,
            intArrayOf(
                Color.argb((180 * (0.45f + smoothedAudioLevel * 0.35f)).toInt().coerceIn(0, 255), 72, 52, 204), // #4834cc
                Color.argb((130 * (0.20f + smoothedAudioLevel * 0.25f)).toInt().coerceIn(0, 255), 127, 20, 88), // #7f1458
                Color.TRANSPARENT
            ),
            floatArrayOf(0.15f, 0.55f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvasPaint.shader = outerGlow
        canvasPaint.xfermode = null
        canvas.drawCircle(cx, cy, glowR, canvasPaint)

        // 2. Dynamic multi-blobs (4 organic swirling layers with additive blending)
        canvasPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        val blobColors = listOf(
            Color.rgb((cfg.primaryR * 255).toInt(), (cfg.primaryG * 255).toInt(), (cfg.primaryB * 255).toInt()),
            Color.rgb((cfg.secondaryR * 255).toInt(), (cfg.secondaryG * 255).toInt(), (cfg.secondaryB * 255).toInt()),
            Color.rgb(30, 116, 251), // #1e74fb
            Color.rgb(14, 23, 52)    // #0e1734
        )

        for (i in blobColors.indices) {
            val angle = t * (0.75f + i * 0.30f) + (i * Math.PI.toFloat() / 2.0f)
            var distFactor = 0.12f + 0.04f * sin(t * 1.2f + i)
            if (state == OrbState.LISTENING || state == OrbState.SPEAKING) {
                distFactor += smoothedAudioLevel * 0.20f
            }

            val bx = cx + cos(angle) * (rBase * distFactor)
            val by = cy + sin(angle * 1.25f) * (rBase * distFactor)
            val br = rBase * (0.58f + 0.10f * sin(t * 1.8f + i * 1.4f) + smoothedAudioLevel * 0.20f)

            val baseCol = blobColors[i]
            val blobShader = RadialGradient(
                bx, by, br,
                intArrayOf(baseCol, Color.argb(128, Color.red(baseCol), Color.green(baseCol), Color.blue(baseCol)), Color.TRANSPARENT),
                floatArrayOf(0.05f, 0.5f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvasPaint.shader = blobShader
            canvas.drawCircle(bx, by, br, canvasPaint)
        }

        // 3. Inner core highlights (bright crystal center)
        val coreR = rBase * (0.35f + 0.05f * sin(t * 2.0f) + smoothedAudioLevel * 0.12f)
        val coreShader = RadialGradient(
            cx, cy, coreR,
            intArrayOf(
                Color.WHITE,
                Color.argb(180, (cfg.primaryR * 255).toInt(), (cfg.primaryG * 255).toInt(), (cfg.primaryB * 255).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.0f, 0.4f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvasPaint.shader = coreShader
        canvas.drawCircle(cx, cy, coreR, canvasPaint)

        // 4. Subtle chromatic edge rim
        canvasPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        val rimR = min(maxR * 0.94f, rBase * 1.02f)
        val rimShader = RadialGradient(
            cx, cy, rimR,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(90, 240, 245, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.80f, 0.92f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvasPaint.shader = rimShader
        canvas.drawCircle(cx, cy, rimR, canvasPaint)
    }
}
