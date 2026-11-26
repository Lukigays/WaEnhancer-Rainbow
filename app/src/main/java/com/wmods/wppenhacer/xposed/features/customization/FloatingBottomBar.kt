package com.wmods.wppenhacer.xposed.features.customization

import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.ViewHookDispatcher
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import java.lang.reflect.Method
import java.util.WeakHashMap

class FloatingBottomBar(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        private const val CORNER_RADIUS_DP = 24f
        private const val SIDE_MARGIN_DP = 12f
        private const val BOTTOM_MARGIN_DP = 16f
        private const val ELEVATION_DP = 4f
        private const val FAB_GAP_DP = 8f

        // Selection lens metrics (Liquid Glass, like the iPhone photo).
        private const val LENS_INSET_DP = 7f      // glow space around the capsule
        private const val LENS_ITEM_INSET_DP = 6f // capsule margin inside the item view
        private const val LENS_MIN_H_DP = 30f
        private const val LENS_MAX_H_DP = 40f
        private const val LENS_SLIDE_MS = 320L
        private const val LENS_WATCH_MS = 250L

        private const val PREF_RAINBOW = "floating_bottom_bar_rainbow"
        private const val PREF_RAINBOW_INTENSITY = "floating_bottom_bar_rainbow_intensity"

        private const val KEY_BAR_LIGHT = "wae_floating_bar_light"
        private const val KEY_LENS_INSTALLED = "wae_floating_lens_installed"
        private const val KEY_LENS_PLACED = "wae_lens_placed"
        private const val KEY_LENS_ANIM = "wae_lens_anim"
        private const val KEY_FAB_GLASS = "wae_fab_glass"
        private const val KEY_SEL_METHOD = "wae_sel_method"

        private val FAB_RESOURCE_NAMES = arrayOf("fab", "fab_second", "extended_mini_fab")
        private const val MAX_LENS_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 100L
    }

    private val processedBars = WeakHashMap<ViewGroup, Boolean>()
    private val setupAttempts = WeakHashMap<ViewGroup, Int>()
    private val lensAttempts = WeakHashMap<ViewGroup, Int>()
    private val baseBottomPadding = WeakHashMap<ViewGroup, Int>()
    private val paddingGuarded = WeakHashMap<ViewGroup, Boolean>()

    override fun doHook() {
        if (!xprefs.getBoolean("floating_bottom_bar", false)) return

        val bottomNavId = Utils.getID("bottom_nav", "id")
        if (bottomNavId <= 0) return
        val fabIds = FAB_RESOURCE_NAMES.mapNotNull { name ->
            Utils.getID(name, "id").takeIf { id -> id > 0 }
        }.toSet()

        ViewHookDispatcher.onAttachedToWindow(this) { view ->
            if (view.id == bottomNavId) {
                val bar = view as? ViewGroup ?: return@onAttachedToWindow
                scheduleSetup(bar)
                return@onAttachedToWindow
            }
            if (view.id in fabIds) {
                view.post { positionFabAboveCurrentBar(view, bottomNavId) }
            }
        }

        ViewHookDispatcher.onDetachedFromWindow(this) { view ->
            if (view.id != bottomNavId) return@onDetachedFromWindow
            val bar = view as? ViewGroup ?: return@onDetachedFromWindow
            setupAttempts.remove(bar)
            processedBars.remove(bar)
        }
    }

    private fun scheduleSetup(bar: ViewGroup) {
        if (processedBars.containsKey(bar)) {
            ensureBarOverlay(bar)
            return
        }

        bar.post {
            if (setupFloatingBar(bar)) {
                processedBars[bar] = true
                setupAttempts.remove(bar)
                return@post
            }
            retrySetup(bar)
        }
    }

    private fun retrySetup(bar: ViewGroup) {
        val attempt = setupAttempts[bar] ?: 0
        if (attempt >= 3) return
        setupAttempts[bar] = attempt + 1
        bar.postDelayed({
            if (processedBars.containsKey(bar)) return@postDelayed
            if (setupFloatingBar(bar)) {
                processedBars[bar] = true
                setupAttempts.remove(bar)
            } else {
                retrySetup(bar)
            }
        }, RETRY_DELAY_MS)
    }

    private fun ensureBarOverlay(bar: ViewGroup) {
        val container = bar.parent as? ViewGroup ?: return
        if (container.parent !is FrameLayout) {
            bar.post { setupFloatingBar(bar) }
        }
    }

    private fun setupFloatingBar(bar: ViewGroup): Boolean {
        try {
            val container = bar.parent as? ViewGroup ?: return false
            val rootView = findRootView(bar) ?: return false
            if (container.parent === rootView) {
                updateOverlayLayout(rootView, container, bar)
                applyTransparentShadowStyle(container, bar)
                positionFabsAboveBar(rootView, container)
                return true
            }

            (container.parent as? ViewGroup)?.removeView(container)

            val rootParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = navigationBarInset(rootView) + Utils.dipToPixels(BOTTOM_MARGIN_DP)
            }
            rootView.addView(container, rootParams)

            applyTransparentShadowStyle(container, bar)
            positionFabsAboveBar(rootView, container)

            logDebug("FloatingBottomBar applied as overlay")
            return true
        } catch (e: Throwable) {
            log(e)
            return false
        }
    }

    private fun updateOverlayLayout(rootView: FrameLayout, container: ViewGroup, bar: ViewGroup) {
        val params = container.layoutParams as? FrameLayout.LayoutParams ?: return
        val bottomMargin = navigationBarInset(rootView) + Utils.dipToPixels(BOTTOM_MARGIN_DP)
        if (params.gravity != Gravity.BOTTOM
            || params.width != ViewGroup.LayoutParams.MATCH_PARENT
            || params.height != ViewGroup.LayoutParams.WRAP_CONTENT
            || params.bottomMargin != bottomMargin
        ) {
            params.gravity = Gravity.BOTTOM
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.bottomMargin = bottomMargin
            container.layoutParams = params
        }
        val barParams = bar.layoutParams
        if (barParams.width != ViewGroup.LayoutParams.MATCH_PARENT
            || barParams.height != ViewGroup.LayoutParams.WRAP_CONTENT
        ) {
            barParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            barParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            bar.layoutParams = barParams
        }
    }

    private fun navigationBarInset(view: View): Int {
        return ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?.bottom ?: 0
    }

    private fun findRootView(startView: View): FrameLayout? {
        var current: View? = startView
        var lastFrameLayout: FrameLayout? = null
        while (current != null) {
            if (current is FrameLayout) {
                lastFrameLayout = current
            }
            current = current.parent as? View
        }
        return lastFrameLayout
    }

    private fun applyTransparentShadowStyle(container: ViewGroup, bar: ViewGroup) {
        container.setBackgroundColor(Color.TRANSPARENT)

        val parent = container.parent as? ViewGroup
        parent?.clipChildren = false
        parent?.clipToPadding = false
        container.clipChildren = false
        container.clipToPadding = false

        val dividerId = Utils.getID("bottom_nav_divider", "id")
        if (dividerId > 0) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child.id == dividerId) {
                    child.visibility = View.GONE
                    break
                }
            }
        }

        val barColor = resolveBarColor(bar)
        val lightTheme = isLightColor(barColor)
        ReflectionUtils.setAdditionalInstanceField(bar, KEY_BAR_LIGHT, lightTheme)
        val radiusDp =
            xprefs.getInt("floating_bottom_bar_radius", CORNER_RADIUS_DP.toInt()).toFloat()
        val radius = Utils.dipToPixels(radiusDp).toFloat()

        val existing = bar.background
        if (existing is LiquidGlassBarDrawable) {
            existing.updateStyle(barColor, radius)
        } else {
            bar.background = LiquidGlassBarDrawable(barColor, lightTheme, radius)
        }
        bar.outlineProvider = ViewOutlineProvider.BACKGROUND
        bar.elevation = Utils.dipToPixels(ELEVATION_DP).toFloat()

        val sideMargin = Utils.dipToPixels(SIDE_MARGIN_DP)
        val params = bar.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null && (params.width != ViewGroup.LayoutParams.MATCH_PARENT
                    || params.height != ViewGroup.LayoutParams.WRAP_CONTENT
                    || params.leftMargin != sideMargin || params.topMargin != 0
                    || params.rightMargin != sideMargin || params.bottomMargin != 0)
        ) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.setMargins(sideMargin, 0, sideMargin, 0)
            bar.layoutParams = params
        }

        stripBottomInset(bar)
        installPaddingGuard(bar)
        installSelectionLens(bar)
    }

    private fun stripBottomInset(bar: ViewGroup) {
        val basePadding = baseBottomPadding.getOrPut(bar) {
            (bar.paddingBottom - navigationBarInset(bar)).coerceAtLeast(0)
        }
        if (bar.paddingBottom != basePadding) {
            bar.setPaddingRelative(bar.paddingStart, bar.paddingTop, bar.paddingEnd, basePadding)
        }
    }

    // WDSBottomBar (Material BottomNavigationView) re-applies the navigation bar inset as
    // bottom padding on every insets dispatch (e.g. when switching tabs), which made the
    // floating bar grow taller. The bar already sits above the navigation bar via its margin,
    // so the bottom inset is ignored and the base padding is enforced on every layout.
    private fun installPaddingGuard(bar: ViewGroup) {
        if (paddingGuarded.put(bar, true) != null) return
        ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
            stripBottomInset(view as ViewGroup)
            insets
        }
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            stripBottomInset(bar)
            val params = bar.layoutParams
            if (params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                bar.layoutParams = params
            }
        }
    }

    // ------------------------------------------------------------------
    // Liquid Glass selection lens: a clear glass capsule with an iridescent
    // rainbow fringe that slides over the active tab, like the iPhone photo.
    // ------------------------------------------------------------------

    private fun installSelectionLens(bar: ViewGroup) {
        if (ReflectionUtils.getAdditionalInstanceField(bar, KEY_LENS_INSTALLED) != null) return
        val menu = findMenuContainer(bar)
        if (menu == null) {
            val attempt = lensAttempts[bar] ?: 0
            if (attempt >= MAX_LENS_ATTEMPTS) return
            lensAttempts[bar] = attempt + 1
            bar.postDelayed({ installSelectionLens(bar) }, RETRY_DELAY_MS)
            return
        }
        ReflectionUtils.setAdditionalInstanceField(bar, KEY_LENS_INSTALLED, true)

        // Hide the Material built-in active indicator: our lens replaces it.
        runCatching {
            val setter = bar.javaClass
                .getMethod("setItemActiveIndicatorColor", android.content.res.ColorStateList::class.java)
            setter.invoke(bar, android.content.res.ColorStateList.valueOf(Color.TRANSPARENT))
        }.onFailure { logDebug("Unable to hide the native indicator", it) }

        val lightTheme = ReflectionUtils.getAdditionalInstanceField(bar, KEY_BAR_LIGHT) as? Boolean ?: false
        val inset = Utils.dipToPixels(LENS_INSET_DP)

        val lens = View(menu.context)
        lens.isClickable = false
        lens.isFocusable = false
        val drawable = GlassLensDrawable(lightTheme)
        drawable.insetPx = inset.toFloat()
        drawable.rimWidthPx = Utils.dipToPixels(2f).toFloat()
        drawable.ringWidthPx = Utils.dipToPixels(2.5f).toFloat()
        drawable.fringeWidthPx = Utils.dipToPixels(2f).toFloat()
        drawable.glowWidthPx = Utils.dipToPixels(8f).toFloat()
        drawable.rainbowEnabled = xprefs.getBoolean(PREF_RAINBOW, true)
        drawable.rainbowIntensity =
            (xprefs.getInt(PREF_RAINBOW_INTENSITY, 50) / 100f).coerceIn(0.1f, 1f)
        lens.background = drawable

        menu.addView(lens, 0, FrameLayout.LayoutParams(0, 0))
        ReflectionUtils.setAdditionalInstanceField(lens, KEY_LENS_PLACED, false)

        menu.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                syncLens(bar, menu, lens)
            }
        }

        // Fast reaction to taps without touching WhatsApp's own listener.
        menu.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                v.postDelayed({ syncLens(bar, menu, lens) }, 90L)
            }
            false
        }

        // Lightweight watcher so the lens never drifts out of place.
        bar.post(object : Runnable {
            override fun run() {
                if (!bar.isAttachedToWindow) return
                syncLens(bar, menu, lens)
                bar.postDelayed(this, LENS_WATCH_MS)
            }
        })
    }

    private fun findMenuContainer(bar: ViewGroup): ViewGroup? {
        // Material BottomNavigationView hosts its item views inside one menu child.
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i) as? ViewGroup ?: continue
            if (child.childCount >= 2 && child.getChildAt(0) is ViewGroup) return child
        }
        // Fallback: items laid out directly in the bar.
        var itemLike = 0
        for (i in 0 until bar.childCount) {
            if (bar.getChildAt(i) is ViewGroup) itemLike++
        }
        if (itemLike >= 2) return bar
        return null
    }

    private fun selectedItemId(bar: ViewGroup): Int {
        return runCatching {
            val method = ReflectionUtils.getAdditionalInstanceField(bar, KEY_SEL_METHOD) as? Method
                ?: bar.javaClass.getMethod("getSelectedItemId").also {
                    ReflectionUtils.setAdditionalInstanceField(bar, KEY_SEL_METHOD, it)
                }
            (method.invoke(bar) as? Int) ?: 0
        }.getOrDefault(0)
    }

    private fun syncLens(bar: ViewGroup, menu: ViewGroup, lens: View) {
        if (!bar.isAttachedToWindow || !lens.isAttachedToWindow) return
        if (lens.visibility != View.VISIBLE) lens.visibility = View.VISIBLE

        val inset = Utils.dipToPixels(LENS_INSET_DP)
        val items = (1 until menu.childCount).mapNotNull { index ->
            (menu.getChildAt(index) as? View)?.takeIf { it.visibility == View.VISIBLE }
        }
        if (items.isEmpty()) return

        val reference = items[0]
        val targetWidth = (reference.width - Utils.dipToPixels(LENS_ITEM_INSET_DP) * 2)
            .coerceAtLeast(Utils.dipToPixels(48))
        val targetHeight = (reference.height - Utils.dipToPixels(LENS_ITEM_INSET_DP) * 2)
            .coerceIn(Utils.dipToPixels(LENS_MIN_H_DP), Utils.dipToPixels(LENS_MAX_H_DP))
        val params = lens.layoutParams
        if (params != null && (params.width != targetWidth + inset * 2
                    || params.height != targetHeight + inset * 2)
        ) {
            params.width = targetWidth + inset * 2
            params.height = targetHeight + inset * 2
            lens.layoutParams = params
        }

        val selectedId = selectedItemId(bar)
        val target = (if (selectedId != 0) items.firstOrNull { it.id == selectedId } else null)
            ?: items.firstOrNull { it.isSelected }
            ?: return

        val x = target.left + target.width / 2f - lens.width / 2f
        val placed = ReflectionUtils.getAdditionalInstanceField(lens, KEY_LENS_PLACED) as? Boolean ?: false
        if (!placed) {
            ReflectionUtils.setAdditionalInstanceField(lens, KEY_LENS_PLACED, true)
            lens.translationX = x
            lens.alpha = 0f
            lens.animate().alpha(1f).setDuration(220L).start()
            return
        }

        val current = lens.translationX
        if (Math.abs(x - current) <= 2f) return

        (ReflectionUtils.getAdditionalInstanceField(lens, KEY_LENS_ANIM) as? ValueAnimator)?.cancel()
        val anim = ValueAnimator.ofFloat(current, x).apply {
            duration = LENS_SLIDE_MS
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                lens.translationX = it.animatedValue as Float
                val pulse = Math.sin(Math.PI * it.animatedFraction).toFloat()
                lens.scaleX = 1f + 0.09f * pulse
                lens.scaleY = 1f + 0.07f * pulse
            }
        }
        ReflectionUtils.setAdditionalInstanceField(lens, KEY_LENS_ANIM, anim)
        anim.start()
    }

    // ------------------------------------------------------------------
    // FABs: same glass treatment as the search button in the photo.
    // ------------------------------------------------------------------

    private fun styleFabGlass(fab: View, lightTheme: Boolean) {
        if (ReflectionUtils.getAdditionalInstanceField(fab, KEY_FAB_GLASS) != null) return
        ReflectionUtils.setAdditionalInstanceField(fab, KEY_FAB_GLASS, true)
        val drawable = GlassLensDrawable(lightTheme)
        drawable.insetPx = Utils.dipToPixels(4f).toFloat()
        drawable.rimWidthPx = Utils.dipToPixels(2f).toFloat()
        drawable.ringWidthPx = Utils.dipToPixels(2f).toFloat()
        drawable.fringeWidthPx = Utils.dipToPixels(1.5f).toFloat()
        drawable.glowWidthPx = Utils.dipToPixels(6f).toFloat()
        drawable.rainbowEnabled = xprefs.getBoolean(PREF_RAINBOW, true)
        drawable.rainbowIntensity =
            (xprefs.getInt(PREF_RAINBOW_INTENSITY, 50) / 100f).coerceIn(0.1f, 1f)
        fab.background = drawable
        fab.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun positionFabsAboveBar(rootView: ViewGroup, container: ViewGroup) {
        val barHeight = container.height
        if (barHeight <= 0) {
            container.postDelayed({ positionFabsAboveBar(rootView, container) }, RETRY_DELAY_MS)
            return
        }

        val lightTheme =
            (ReflectionUtils.getAdditionalInstanceField(rootView, KEY_BAR_LIGHT) as? Boolean)
                ?: isLightColor(resolveBarColor(container))
        val offset = -(barHeight + Utils.dipToPixels(FAB_GAP_DP)).toFloat()
        for (name in FAB_RESOURCE_NAMES) {
            val id = Utils.getID(name, "id")
            if (id <= 0) continue
            rootView.findViewById<View>(id)?.let { fab ->
                fab.translationY = offset
                styleFabGlass(fab, lightTheme)
                bringToFrontIfNeeded(fab)
            }
        }
    }

    private fun positionFabAboveCurrentBar(fab: View, bottomNavId: Int) {
        val bottomNav = fab.rootView.findViewById<View>(bottomNavId) ?: return
        val container = bottomNav.parent as? ViewGroup ?: return
        if (container.parent !is FrameLayout) return
        val lightTheme =
            (ReflectionUtils.getAdditionalInstanceField(bottomNav, KEY_BAR_LIGHT) as? Boolean)
                ?: isLightColor(resolveBarColor(bottomNav as? ViewGroup ?: container))
        styleFabGlass(fab, lightTheme)
        positionFab(fab, container)
    }

    private fun positionFab(fab: View, container: ViewGroup) {
        val barHeight = container.height
        if (barHeight <= 0) {
            container.postDelayed({ positionFab(fab, container) }, RETRY_DELAY_MS)
            return
        }

        fab.translationY = -(barHeight + Utils.dipToPixels(FAB_GAP_DP)).toFloat()
        bringToFrontIfNeeded(fab)
    }

    private fun bringToFrontIfNeeded(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        if (parent.indexOfChild(view) != parent.childCount - 1) view.bringToFront()
    }

    private fun resolveBarColor(bar: ViewGroup): Int {
        val currentBg: Drawable? = bar.background
        if (currentBg is ColorDrawable) {
            return currentBg.color
        }
        return try {
            val typedValue = TypedValue()
            bar.context.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
            typedValue.data
        } catch (_: Throwable) {
            0xFF121212.toInt()
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return (red * 299 + green * 587 + blue * 114) / 1000 > 180
    }

    override fun getPluginName(): String {
        return "Floating Bottom Bar"
    }
}
