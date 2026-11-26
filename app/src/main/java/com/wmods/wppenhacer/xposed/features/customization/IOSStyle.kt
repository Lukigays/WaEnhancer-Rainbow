package com.wmods.wppenhacer.xposed.features.customization

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadBallonBorderDrawable
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadBubbleDrawableMethod
import com.wmods.wppenhacer.xposed.utils.Utils
import kotlin.math.min

/**
 * iOS Chat Style: replaces WhatsApp's tailed bubble drawables with clean,
 * fully-rounded iOS-style capsules - outgoing teal green / incoming dark gray
 * (light theme: outgoing light mint / incoming white), like iOS WhatsApp.
 *
 * Key details that keep WhatsApp working:
 *  - The wrapper drawable delegates getPadding()/intrinsic sizes/state to the
 *    original drawable, so content insets and the long-press context popup
 *    (reaction bar) keep anchoring exactly like stock WhatsApp.
 *  - Media bubbles use a transparent background: on iOS, photos/videos float
 *    as rounded media without a colored bubble behind them. This also removes
 *    the old 9-patch frame that produced corner artifacts on media bubbles.
 */
class IOSStyle(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        // iOS WhatsApp bubble palette.
        private const val DARK_RIGHT = 0xFF0F766E.toInt() // teal green (outgoing)
        private const val DARK_LEFT = 0xFF26282A.toInt()  // dark gray (incoming)
        private const val LIGHT_RIGHT = 0xFFD9FDD3.toInt() // light mint (outgoing)
        private const val LIGHT_LEFT = 0xFFFFFFFF.toInt()  // white (incoming)
        private const val RADIUS_DP = 18f
    }

    override fun doHook() {
        if (!xprefs.getBoolean("ios_style", false)) return
        // Manual bubble colors take precedence when enabled.
        if (xprefs.getBoolean("bubble_color", false)) return

        val dark = isDarkTheme()
        val rightColor = if (dark) DARK_RIGHT else LIGHT_RIGHT
        val leftColor = if (dark) DARK_LEFT else LIGHT_LEFT
        val radius = Utils.dipToPixels(RADIUS_DP).toFloat()

        val mainBubble = loadBubbleDrawableMethod(classLoader)
        mainBubble.hook {
            after {
                val original = result as? Drawable ?: return@after
                val position = args[0] as? Int ?: return@after
                result = IOSBubbleDrawable(
                    original,
                    if (position == 3) rightColor else leftColor,
                    radius
                )
            }
        }

        val borderBubble = loadBallonBorderDrawable(classLoader)
        borderBubble.hook {
            after {
                // Same rounded capsule for the border variant (used by some bubble
                // layouts, e.g. media). Same bounds + radius = perfectly aligned,
                // so the old 9-patch corner artifacts are gone.
                val original = result as? Drawable ?: return@after
                val position = args.getOrNull(1) as? Int ?: 1
                result = IOSBubbleDrawable(
                    original,
                    if (position == 3) rightColor else leftColor,
                    radius
                )
            }
        }
    }

    private fun isDarkTheme(): Boolean {
        return try {
            val mask = Utils.application.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            mask == Configuration.UI_MODE_NIGHT_YES
        } catch (_: Throwable) {
            true
        }
    }

    override fun getPluginName(): String {
        return "iOS Chat Style"
    }
}

/**
 * Rounded iOS bubble that keeps everything WhatsApp expects from the original
 * drawable: padding (content insets + popup anchoring), intrinsic size and
 * state handling. Draws a plain rounded capsule - no tail, no artifacts.
 */
class IOSBubbleDrawable(
    private val original: Drawable,
    private val bubbleColor: Int,
    private val radius: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bubbleColor }
    private val rect = RectF()

    init {
        state = original.state
        bounds = original.bounds
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val r = min(radius, min(rect.width(), rect.height()) / 2f)
        canvas.drawRoundRect(rect, r, r, paint)
    }

    // Critical: WhatsApp uses the bubble drawable padding for content insets
    // and to anchor the long-press popup / reaction bar. Delegating it keeps
    // long-press working exactly like stock.
    override fun getPadding(padding: Rect): Boolean = original.getPadding(padding)

    override fun getIntrinsicWidth(): Int = original.intrinsicWidth

    override fun getIntrinsicHeight(): Int = original.intrinsicHeight

    override fun setState(state: IntArray): Boolean {
        val changed = super.setState(state)
        original.state = state
        return changed
    }

    override fun onStateChange(state: IntArray): Boolean {
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        original.bounds = bounds
        invalidateSelf()
    }
}
