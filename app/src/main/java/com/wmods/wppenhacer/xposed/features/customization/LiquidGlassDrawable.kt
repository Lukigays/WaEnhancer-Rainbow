package com.wmods.wppenhacer.xposed.features.customization

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * iOS 26 "Liquid Glass" look, split in two layers like the iPhone photo:
 *
 *  - [LiquidGlassBarDrawable]: the navigation bar itself. Plain dark frosted
 *    glass with a soft specular sheen and a faint rim. No rainbow here.
 *  - [GlassLensDrawable]: the floating selection lens that slides over the
 *    active tab. Clear glass with bright top/bottom rims, a dark inner ring
 *    and an iridescent rainbow fringe + glow (chromatic dispersion).
 */

class LiquidGlassBarDrawable(baseColor: Int, private val lightTheme: Boolean, cornerRadiusPx: Float) :
    Drawable() {

    var cornerRadiusPx: Float = cornerRadiusPx
        private set

    var baseColor: Int = baseColor
        set(value) {
            field = value
            rebuildFill()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    init {
        rebuildFill()
    }

    fun updateStyle(color: Int, radiusPx: Float) {
        baseColor = color
        cornerRadiusPx = radiusPx
        rebuildFill()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildFill()
    }

    private fun rebuildFill() {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val h = rect.height().coerceAtLeast(1f)
        if (lightTheme) {
            fillPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                applyAlpha(blend(baseColor, 0xFFFFFFFF.toInt(), 0.30f), 0.82f),
                applyAlpha(blend(baseColor, 0xFFE3E9EF.toInt(), 0.42f), 0.88f),
                Shader.TileMode.CLAMP
            )
        } else {
            fillPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                applyAlpha(blend(baseColor, 0xFF10161D.toInt(), 0.25f), 0.85f),
                applyAlpha(blend(baseColor, 0xFF04070A.toInt(), 0.55f), 0.88f),
                Shader.TileMode.CLAMP
            )
        }
        sheenPaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + h * 0.55f,
            Color.argb(16, 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        rimPaint.shader = null
        rimPaint.style = Paint.Style.STROKE
        rimPaint.strokeWidth = 2f
        rimPaint.color = if (lightTheme) Color.argb(30, 0, 0, 0) else Color.argb(34, 255, 255, 255)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = min(cornerRadiusPx, min(rect.width(), rect.height()) / 2f)
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.drawPath(path, fillPaint)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawPaint(sheenPaint)
        canvas.restore()
        canvas.drawPath(path, rimPaint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(
            bounds, min(cornerRadiusPx, min(bounds.width(), bounds.height()) / 2f)
        )
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun applyAlpha(color: Int, fraction: Float): Int {
        val alpha = (fraction.coerceIn(0f, 1f) * 255f).toInt()
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun blend(base: Int, overlay: Int, fraction: Float): Int {
        val inv = 1f - fraction
        val r = (Color.red(base) * inv + Color.red(overlay) * fraction).toInt().coerceIn(0, 255)
        val g = (Color.green(base) * inv + Color.green(overlay) * fraction).toInt().coerceIn(0, 255)
        val b = (Color.blue(base) * inv + Color.blue(overlay) * fraction).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}

class GlassLensDrawable(private val lightTheme: Boolean) : Drawable() {

    companion object {
        // Iridescent stops, first == last so the SweepGradient wraps seamlessly.
        private val RAINBOW = intArrayOf(
            0xFFFF5A76.toInt(), // rose
            0xFFFF9E45.toInt(), // amber
            0xFFFFD84D.toInt(), // gold
            0xFF7CE685.toInt(), // mint
            0xFF41D8FF.toInt(), // cyan
            0xFF5E82FF.toInt(), // azure
            0xFFB45EFF.toInt(), // violet
            0xFFFF5A76.toInt()  // wrap -> rose
        )
        private val RAINBOW_STOPS =
            floatArrayOf(0f, 0.143f, 0.286f, 0.429f, 0.571f, 0.714f, 0.857f, 1f)
        private const val ANIM_DURATION_MS = 9000L
    }

    var rainbowEnabled = true
    var rainbowIntensity = 0.55f

    // Extra space around the capsule so the glow is not clipped by view bounds.
    var insetPx = 0f
    var rimWidthPx = 2f
    var ringWidthPx = 3f
    var fringeWidthPx = 2f
    var glowWidthPx = 8f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fringePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val capsule = RectF()
    private val inner = RectF()
    private val capsulePath = Path()
    private val innerPath = Path()
    private val rotationMatrix = Matrix()
    private val translateMatrix = Matrix()

    private var sweepShader: SweepGradient? = null
    private var animator: ValueAnimator? = null
    private var phase = 0f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        sweepShader = null
    }

    private fun ensureShader(): SweepGradient? {
        if (bounds.isEmpty) return null
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        var shader = sweepShader
        if (shader == null) {
            shader = SweepGradient(cx, cy, RAINBOW, RAINBOW_STOPS)
            sweepShader = shader
        }
        rotationMatrix.setRotate(phase * 360f, cx, cy)
        shader.setLocalMatrix(rotationMatrix)
        return shader
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        capsule.set(
            b.left + insetPx, b.top + insetPx, b.right - insetPx, b.bottom - insetPx
        )
        if (capsule.isEmpty) return
        val radius = capsule.height() / 2f
        capsulePath.reset()
        capsulePath.addRoundRect(capsule, radius, radius, Path.Direction.CW)

        // 1. Iridescent glow bleeding outside the capsule.
        val shader = if (rainbowEnabled) ensureShader() ?: return else null
        if (shader != null) {
            glowPaint.shader = shader
            glowPaint.style = Paint.Style.STROKE
            glowPaint.strokeWidth = glowWidthPx
            glowPaint.alpha = (26 + rainbowIntensity * 54).toInt()
            canvas.drawPath(capsulePath, glowPaint)
        }

        // Clip everything else to the capsule.
        val save = canvas.save()
        canvas.clipPath(capsulePath)

        // 2. Clear glass fill.
        fillPaint.color = if (lightTheme) Color.argb(38, 255, 255, 255) else Color.argb(20, 255, 255, 255)
        canvas.drawPaint(fillPaint)

        // 3. Subtle iridescent tint inside the glass.
        if (shader != null) {
            tintPaint.shader = shader
            tintPaint.alpha = (rainbowIntensity * 24).toInt()
            canvas.drawPaint(tintPaint)
        }

        // 4. Specular sheen on the top of the lens.
        sheenPaint.shader = LinearGradient(
            0f, capsule.top, 0f, capsule.top + capsule.height() * 0.45f,
            Color.argb(70, 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPaint(sheenPaint)

        // 5. Moving shimmer band.
        if (shader != null) {
            val travel = capsule.width() * 1.45f
            translateMatrix.setTranslate(
                -capsule.width() * 0.45f + travel * (phase * 1.618f % 1f), 0f
            )
            shimmerPaint.alpha = (26 + rainbowIntensity * 30).toInt()
            shimmerPaint.shader = LinearGradient(
                0f, 0f, capsule.width() * 0.45f, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(150, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            shimmerPaint.shader.setLocalMatrix(translateMatrix)
            canvas.drawPaint(shimmerPaint)
        }
        canvas.restoreToCount(save)

        // 6. Dark inner ring, the "thick glass" edge right inside the bright rim.
        inner.set(capsule)
        inner.inset(rimWidthPx * 1.6f, rimWidthPx * 1.6f)
        if (!inner.isEmpty) {
            innerPath.reset()
            innerPath.addRoundRect(inner, inner.height() / 2f, inner.height() / 2f, Path.Direction.CW)
            ringPaint.shader = null
            ringPaint.style = Paint.Style.STROKE
            ringPaint.strokeWidth = ringWidthPx
            ringPaint.color = if (lightTheme) Color.argb(80, 0, 0, 0) else Color.argb(110, 0, 0, 0)
            canvas.drawPath(innerPath, ringPaint)
        }

        // 7. Rainbow chromatic fringe on the capsule edge.
        if (shader != null) {
            fringePaint.shader = shader
            fringePaint.style = Paint.Style.STROKE
            fringePaint.strokeWidth = fringeWidthPx
            fringePaint.alpha = (80 + rainbowIntensity * 120).toInt()
            canvas.drawPath(capsulePath, fringePaint)
        } else {
            fringePaint.shader = null
            fringePaint.style = Paint.Style.STROKE
            fringePaint.strokeWidth = fringeWidthPx
            fringePaint.color = if (lightTheme) Color.argb(70, 0, 0, 0) else Color.argb(80, 255, 255, 255)
            canvas.drawPath(capsulePath, fringePaint)
        }

        // 8. Bright top / bottom rims, like light catching the glass edge.
        rimPaint.shader = LinearGradient(
            0f, capsule.top, 0f, capsule.bottom,
            intArrayOf(
                Color.argb(160, 255, 255, 255),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(110, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.30f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        rimPaint.style = Paint.Style.STROKE
        rimPaint.strokeWidth = rimWidthPx
        canvas.drawPath(capsulePath, rimPaint)
    }

    override fun getOutline(outline: Outline) {
        val r = min(bounds.width(), bounds.height()) / 2f
        outline.setRoundRect(bounds, r)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) startAnimation() else animator?.cancel()
        return changed
    }

    private fun startAnimation() {
        val anim = animator ?: ValueAnimator.ofFloat(0f, 1f).also { created ->
            created.duration = ANIM_DURATION_MS
            created.interpolator = LinearInterpolator()
            created.repeatCount = ValueAnimator.INFINITE
            created.addUpdateListener {
                phase = it.animatedValue as Float
                if (isVisible) invalidateSelf()
            }
            animator = created
        }
        if (!anim.isRunning) anim.start()
    }
}
