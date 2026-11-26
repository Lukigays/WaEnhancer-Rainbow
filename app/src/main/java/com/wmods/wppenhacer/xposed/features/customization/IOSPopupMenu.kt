package com.wmods.wppenhacer.xposed.features.customization

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ActionMode
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hookAll
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.Utils
import java.lang.ref.WeakReference

/**
 * iOS Popup Menu: replaces WhatsApp's top action-mode toolbar on message
 * long-press with a floating iOS-style context menu (rounded dark panel,
 * label left / icon right, red destructive row) attached below the reaction
 * pill popup - like the iPhone reference.
 *
 * How it stays functional:
 *  - The real ActionMode is captured when it starts (View.startActionMode).
 *  - Panel rows call Menu.performIdentifierAction(itemId, 0) on that mode,
 *    so every action (Reply, Star, Forward, Copy, Pin, Report, Delete...)
 *    keeps working with localized titles.
 *  - The stock action-mode bar is hidden while our panel is shown.
 */
class IOSPopupMenu(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        private const val PREF_KEY = "ios_popup_menu"

        private const val PANEL_COLOR = 0xF01C1C1E.toInt() // iOS secondary background ~95% black
        private const val SEPARATOR_COLOR = 0xFF3A3A3C.toInt()
        private const val ROW_TEXT_COLOR = 0xFFF2F2F7.toInt()
        private const val DESTRUCTIVE_COLOR = 0xFFFF453A.toInt()
        private val DESTRUCTIVE_WORDS = arrayOf(
            "hapus", "delete", "eliminar", "löschen", "удалить", "削除", "删除", "supprimer"
        )

        private val handler = Handler(Looper.getMainLooper())
        private var lastActionMode: WeakReference<ActionMode>? = null
    }

    override fun doHook() {
        if (!xprefs.getBoolean(PREF_KEY, true)) return

        // Track the most recent ActionMode: the message context menu starts one
        // at the same moment the reaction popup appears.
        runCatching {
            View::class.java.resolve().method { name("startActionMode") }.hookAll {
                after {
                    val mode = result as? ActionMode ?: return@after
                    lastActionMode = WeakReference(mode)
                }
            }
        }.onFailure { logDebug("Unable to hook startActionMode", it) }

        val popupWindowClass = runCatching {
            Unobfuscator.loadPopupWindowMessageClass(classLoader)
        }.getOrNull() ?: run {
            logDebug("PopupWindowMessage class not found")
            return
        }

        popupWindowClass.resolve().constructor { }.hookAll {
            after {
                val popup = instance as? PopupWindow ?: return@after
                val content = popup.contentView as? ViewGroup ?: return@after
                val activity = WppCore.getCurrentActivity() ?: return@after
                // Let WhatsApp finish inflating the reaction tray first, then
                // append our iOS panel and hide the stock toolbar.
                content.postDelayed({
                    runCatching { buildIOSPanel(popup, content, activity) }
                        .onFailure { logDebug("iOS popup menu failed", it) }
                }, 150L)
            }
        }
    }

    private fun buildIOSPanel(popup: PopupWindow, content: ViewGroup, activity: Activity) {
        val mode = lastActionMode?.get() ?: return
        val menu = mode.menu ?: return
        // Text-selection / toolbar action modes carry very few items; the
        // message context menu always has several.
        val items = (0 until menu.size()).mapNotNull { menu.getItem(it) }
            .filter { !it.title.isNullOrBlank() }
        if (items.size < 3) return

        // Hide the stock action-mode toolbar (our panel replaces it).
        hideActionModeBar(activity)

        val context = content.context
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Utils.dipToPixels(14f).toFloat()
                setColor(PANEL_COLOR)
            }
            elevation = Utils.dipToPixels(16f).toFloat()
            val pad = Utils.dipToPixels(4f)
            setPadding(pad, pad, pad, pad)
        }

        items.forEachIndexed { index, item ->
            val destructive = isDestructive(item.title.toString())

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    Utils.dipToPixels(16f), 0,
                    Utils.dipToPixels(14f), 0
                )
                minimumHeight = Utils.dipToPixels(44f)
                isClickable = true
                isFocusable = true
                val highlight = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = Utils.dipToPixels(10f).toFloat()
                    setColor(Color.TRANSPARENT)
                }
                foreground = highlight

                val label = TextView(context).apply {
                    text = item.title
                    textSize = 16f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    setTextColor(if (destructive) DESTRUCTIVE_COLOR else ROW_TEXT_COLOR)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                addView(label)

                val icon = item.icon
                if (icon != null) {
                    addView(ImageView(context).apply {
                        setImageDrawable(icon.current)
                        setColorFilter(
                            if (destructive) DESTRUCTIVE_COLOR else ROW_TEXT_COLOR
                        )
                        val s = Utils.dipToPixels(20f)
                        layoutParams = LinearLayout.LayoutParams(s, s).apply {
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    })
                }

                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            highlight.setColor(0x24FFFFFF)
                            v.invalidate()
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            highlight.setColor(Color.TRANSPARENT)
                            v.invalidate()
                            // Run the action FIRST (WhatsApp's popup dismiss
                            // listener closes the ActionMode), then close the
                            // menu - iOS closes it right after the tap.
                            runCatching {
                                menu.performIdentifierAction(item.itemId, 0)
                            }
                            popup.dismiss()
                            handler.postDelayed({
                                runCatching { mode.finish() }
                            }, 400L)
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            highlight.setColor(Color.TRANSPARENT)
                            v.invalidate()
                            true
                        }
                        else -> true
                    }
                }
            }
            panel.addView(row)

            if (index != items.lastIndex) {
                panel.addView(View(context).apply {
                    setBackgroundColor(SEPARATOR_COLOR)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Utils.dipToPixels(1)
                    )
                })
            }
        }

        content.addView(
            panel,
            LinearLayout.LayoutParams(
                Utils.dipToPixels(210f), ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun hideActionModeBar(activity: Activity) {
        runCatching {
            val barId = Utils.getID("action_mode_bar", "id")
            if (barId > 0) {
                val decor = activity.window?.decorView as? ViewGroup ?: return
                decor.findViewById<View>(barId)?.visibility = View.GONE
            }
        }
    }

    private fun isDestructive(title: String): Boolean {
        val lower = title.lowercase()
        return DESTRUCTIVE_WORDS.any { lower.contains(it) }
    }

    override fun getPluginName(): String = "iOS Popup Menu"
}

/** Simple transparent spacer drawable helper (unused externally). */
class IOSPanelSpacer(color: Int) : Drawable() {
    private val paint = android.graphics.Paint().apply { this.color = color }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSPARENT
}
