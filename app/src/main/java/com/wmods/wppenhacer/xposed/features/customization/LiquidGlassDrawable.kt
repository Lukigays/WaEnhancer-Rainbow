package com.wmods.wppenhacer.xposed.features.customization

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * iOS 26 "Liquid Glass" look, exactly like the reference photos:
 *
 *  - [LiquidGlassBarDrawable]: the navigation bar itself. Plain dark frosted
 *    glass with a soft specular sheen and a faint rim. No colors at all.
 *  - [GlassLensView]: the floating selection lens that slides over the active
 *    tab. Neutral clear glass: it MAGNIFIES the real content beneath it
 *    (refraction), carries a thick dark inner ring, bright top/bottom rims,
 *    a soft drop shadow and a subtle white sheen. No rainbow, no colors.
 *    The lens never animates on its own: it moves only when the selection
 *    changes (tapping a tab) or when the user drags across the tab bar,
 *    then settles on the selected tab.
 */

class LiquidGlassBarDrawable(baseColor: Int, private val lightTheme: Boolean, cornerRadiusPx: Float) :
    Drawable() {

    var cornerRadiusPx: Float = cornerRadiusPx
        private set

    // Multiplier on the white rim alpha; raised for round FAB buttons.
    var rimStrength = 1f

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
        rimPaint.color = if (lightTheme) {
            Color.argb((30 * rimStrength).toInt().coerceAtMost(255), 0, 0, 0)
        } else {
            Color.argb((34 * rimStrength).toInt().coerceAtMost(255), 255, 255, 255)
        }
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

    override fun getOutline(outline: android.graphics.Outline) {
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

/**
 * The magnifying selection lens. Neutral iOS glass: no rainbow, no colors.
 * Re-renders the bar into an offscreen bitmap and draws it back magnified
 * inside the capsule, so the tab content under the lens is really magnified.
 * It only moves when the selection changes or while the user drags it.
 */
class GlassLensView(context: Context) : View(context) {

    // Padding inside this view around the capsule, reserved for the outer glow.
    var glowPadPx = 6f
    var magnification = 1.32f

    private var bar: ViewGroup? = null

    private val capsule = RectF()
    private val innerRect = RectF()
    private val capsulePath = Path()
    private val innerPath = Path()
    private val ringPath = Path()
    private val drawMatrix = Matrix()
    private val offsetMatrix = Matrix()

    private var captureBitmap: Bitmap? = null
    private var captureCanvas: Canvas? = null
    private var lastCaptureAt = 0L
    private var slideAnim: ValueAnimator? = null

    // Lens center in bar coordinates: what the magnified window samples.
    private var anchorXInBar = 0f
    private var anchorYInBar = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val chromaPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val redMatrix = ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    private val blueMatrix = ColorMatrix(
        floatArrayOf(
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    init {
        chromaPaint.colorFilter = ColorMatrixColorFilter(redMatrix)
    }

    fun bindBar(target: ViewGroup) {
        bar = target
    }

    /** Re-renders the bar content into the offscreen bitmap (throttled). */
    fun requestCapture(force: Boolean) {
        val source = bar ?: return
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastCaptureAt < CAPTURE_THROTTLE_MS) return
        lastCaptureAt = now
        var bmp = captureBitmap
        if (bmp == null || bmp.width != w || bmp.height != h || bmp.isRecycled) {
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            captureBitmap = bmp
            captureCanvas = Canvas(bmp)
        }
        val canvas = captureCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        source.draw(canvas)
        if (anchorXInBar <= 0f) anchorXInBar = w / 2f
        if (anchorYInBar <= 0f) anchorYInBar = h / 2f
        invalidate()
    }

    /** Slides the lens so its capsule centers on (centerX, centerY) in parent coords.
     *  anchorXInBar = lens center in BAR coordinates: the magnified content scrolls
     *  under the glass while the lens travels, exactly like iOS Liquid Glass. */
    fun slideTo(centerX: Float, centerY: Float, anchorXInBar: Float, animated: Boolean) {
        translationY = centerY - height / 2f
        val targetX = centerX - width / 2f
        if (!animated || width == 0) {
            slideAnim?.cancel()
            translationX = targetX
            scaleX = 1f
            scaleY = 1f
            this.anchorXInBar = anchorXInBar
            this.anchorYInBar = bar?.height?.div(2f) ?: anchorYInBar
            requestCapture(false)
            return
        }
        requestCapture(true)
        slideAnim?.cancel()
        val startX = translationX
        val startAnchor = this.anchorXInBar
        val targetAnchor = anchorXInBar
        // Already at the target: settle instantly, never pulse while idle.
        if (Math.abs(targetX - startX) < 2f) {
            this.anchorXInBar = targetAnchor
            requestCapture(false)
            return
        }
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SLIDE_DURATION_MS
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val f = it.animatedValue as Float
                translationX = startX + (targetX - startX) * f
                this@GlassLensView.anchorXInBar = startAnchor + (targetAnchor - startAnchor) * f
                // Stretch while travelling, settle when arriving (iOS jelly feel).
                val pulse = Math.sin(Math.PI * f).toFloat()
                scaleX = 1f + 0.10f * pulse
                scaleY = 1f + 0.08f * pulse
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scaleX = 1f
                    scaleY = 1f
                    this@GlassLensView.anchorXInBar = targetAnchor
                    requestCapture(true)
                }
            })
        }
        slideAnim = anim
        anim.start()
    }

    /** Direct positioning while the user drags across the bar: no animation at
     *  all, the glass simply follows the finger and the magnified content
     *  scrolls beneath it. minX/maxX clamp the capsule inside the bar. */
    fun dragTo(centerX: Float, centerY: Float, anchorXInBar: Float, minX: Float, maxX: Float) {
        slideAnim?.cancel()
        translationY = centerY - height / 2f
        translationX = (centerX - width / 2f).coerceIn(minX, maxX)
        this.anchorXInBar = anchorXInBar
        this.anchorYInBar = bar?.height?.div(2f) ?: anchorYInBar
        requestCapture(true)
    }

    /** Cancels any running slide animation and resets the jelly scale. */
    fun cancelSlide() {
        slideAnim?.cancel()
        scaleX = 1f
        scaleY = 1f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        capsule.set(glowPadPx, glowPadPx, w - glowPadPx, h - glowPadPx)
        if (capsule.isEmpty) return
        val radius = capsule.height() / 2f
        capsulePath.reset()
        capsulePath.addRoundRect(capsule, radius, radius, Path.Direction.CW)

        // Soft outer halo around the capsule.
        glowPaint.style = Paint.Style.STROKE
        glowPaint.shader = null
        glowPaint.color = Color.WHITE
        glowPaint.alpha = 14
        glowPaint.strokeWidth = glowPadPx * 1.3f
        canvas.drawPath(capsulePath, glowPaint)
        glowPaint.alpha = 20
        glowPaint.strokeWidth = glowPadPx * 0.6f
        canvas.drawPath(capsulePath, glowPaint)

        // Soft drop shadow under the lens (floating above the bar, like the photo).
        shadowPaint.style = Paint.Style.STROKE
        shadowPaint.shader = null
        shadowPaint.color = Color.argb(55, 4, 8, 12)
        shadowPaint.strokeWidth = 3.5f
        canvas.save()
        canvas.translate(0f, 2.5f)
        canvas.drawPath(capsulePath, shadowPaint)
        canvas.translate(0f, 2.5f)
        shadowPaint.color = Color.argb(30, 4, 8, 12)
        canvas.drawPath(capsulePath, shadowPaint)
        canvas.restore()

        // Everything else stays inside the capsule.
        val save = canvas.save()
        canvas.clipPath(capsulePath)

        // 1. Base glass tint (visible where the lens bulges past the bar).
        fillPaint.color = Color.argb(22, 255, 255, 255)
        canvas.drawPaint(fillPaint)

        // 2. Magnified real content of the bar beneath the lens. The matrix maps the
        //    bar pixel under the lens center to the capsule center, so the glass
        //    always magnifies exactly what is beneath it — and the content scrolls
        //    through the lens while it slides (anchorXInBar animates with the slide).
        val bmp = captureBitmap
        if (bmp != null && !bmp.isRecycled) {
            val px = if (anchorXInBar > 0f) anchorXInBar else bmp.width / 2f
            val py = if (anchorYInBar > 0f) anchorYInBar else bmp.height / 2f
            drawMatrix.reset()
            drawMatrix.postScale(magnification, magnification)
            drawMatrix.postTranslate(
                capsule.centerX() - magnification * px,
                capsule.centerY() - magnification * py
            )
            canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)

            // 3. Neutral chromatic dispersion on the edge: monochrome red/blue split
            //    (no colors visible, just the thick-glass blur at the rim).
            innerRect.set(capsule)
            innerRect.inset(2.5f, 2.5f)
            innerPath.reset()
            innerPath.addRoundRect(innerRect, innerRect.height() / 2f, innerRect.height() / 2f, Path.Direction.CW)
            ringPath.reset()
            ringPath.op(capsulePath, innerPath, Path.Op.DIFFERENCE)
            val ringSave = canvas.save()
            canvas.clipPath(ringPath)
            chromaPaint.colorFilter = ColorMatrixColorFilter(redMatrix)
            chromaPaint.alpha = 26
            offsetMatrix.set(drawMatrix)
            offsetMatrix.postTranslate(-1.4f, 0f)
            canvas.drawBitmap(bmp, offsetMatrix, chromaPaint)
            chromaPaint.colorFilter = ColorMatrixColorFilter(blueMatrix)
            offsetMatrix.set(drawMatrix)
            offsetMatrix.postTranslate(1.4f, 0f)
            canvas.drawBitmap(bmp, offsetMatrix, chromaPaint)
            canvas.restoreToCount(ringSave)
        }

        // 4. Depth: darker towards the top inside the glass.
        depthPaint.shader = LinearGradient(
            0f, capsule.top, 0f, capsule.top + capsule.height() * 0.38f,
            Color.argb(58, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPaint(depthPaint)

        // 5. Thick dark inner ring, the "thick glass" edge.
        ringPaint.style = Paint.Style.STROKE
        ringPaint.shader = null
        ringPaint.color = Color.argb(95, 4, 7, 10)
        ringPaint.strokeWidth = 9f
        canvas.drawPath(capsulePath, ringPaint)
        ringPaint.color = Color.argb(140, 0, 0, 0)
        ringPaint.strokeWidth = 3.5f
        canvas.drawPath(capsulePath, ringPaint)

        // 6. Bright top/bottom rims where light catches the glass edge.
        rimPaint.shader = LinearGradient(
            0f, capsule.top, 0f, capsule.bottom,
            intArrayOf(
                Color.argb(175, 255, 255, 255),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(125, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.30f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        rimPaint.style = Paint.Style.STROKE
        rimPaint.strokeWidth = 2.2f
        canvas.drawPath(capsulePath, rimPaint)

        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        slideAnim?.cancel()
        captureBitmap?.recycle()
        captureBitmap = null
        captureCanvas = null
    }

    private companion object {
        private const val SLIDE_DURATION_MS = 320L
        private const val CAPTURE_THROTTLE_MS = 90L
    }
}
