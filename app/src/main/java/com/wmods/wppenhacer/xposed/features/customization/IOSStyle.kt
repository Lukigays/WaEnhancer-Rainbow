package com.wmods.wppenhacer.xposed.features.customization

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadBallonBorderDrawable
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadBubbleDrawableMethod
import com.wmods.wppenhacer.xposed.utils.Utils

/**
 * iOS Chat Style: replaces WhatsApp's tailed bubble drawables with clean,
 * fully-rounded iOS-style capsules - outgoing teal green / incoming dark gray
 * (light theme: outgoing light mint / incoming white), like iOS WhatsApp.
 *
 * The message action menu (Star/Reply/Forward/...) and the reaction pill bar
 * shown in the reference screenshots are WhatsApp's own native iOS-style UI on
 * recent versions, so they need no hooking - only the bubbles are restyled.
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
        val radius = Utils.dipToPixels(RADIUS_DP)

        fun iosBubble(isRight: Boolean): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(if (isRight) rightColor else leftColor)
        }

        val mainBubble = loadBubbleDrawableMethod(classLoader)
        mainBubble.hook {
            after {
                val position = args[0] as? Int ?: return@after
                result = iosBubble(position == 3)
            }
        }

        val borderBubble = loadBallonBorderDrawable(classLoader)
        borderBubble.hook {
            after {
                val position = args.getOrNull(1) as? Int ?: return@after
                result = iosBubble(position == 3)
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
