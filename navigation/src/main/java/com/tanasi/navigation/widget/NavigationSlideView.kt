package com.tanasi.navigation.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.res.getResourceIdOrThrow
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.roundToInt
import com.tanasi.navigation.R

class NavigationSlideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    val menu = NavigationSlideMenu(context)
    var headerView: NavigationSlideHeaderView? = null
    val menuView = NavigationSlideMenuView(context).also {
        it.navigationSlideView = this
    }
    private val presenter = NavigationSlidePresenter()
    private val menuInflater: MenuInflater = SupportMenuInflater(context)

    var isOpen = true

    // Rail mode (collapsedWidth/expandedWidth set): the view springs between the two widths.
    private var collapsedWidth = 0
    private var expandedWidth = 0
    private val isRailMode get() = collapsedWidth > 0 && expandedWidth > 0
    private var railWidth = -1f
    private var widthAnimation: SpringAnimation? = null
    private var widthStiffness = 700f
    private var widthDamping = 0.8f

    /** False snaps instead of animating (reduced motion). */
    var animationsEnabled = true

    /** Spring used for the rail width (the app passes its SpatialDefault token). */
    fun setWidthSpring(stiffness: Float, dampingRatio: Float) {
        widthStiffness = stiffness
        widthDamping = dampingRatio
    }

    private var selectedListener: ((item: MenuItem) -> Boolean)? = null
    private var reselectedListener: ((item: MenuItem) -> Boolean)? = null

    /**
     * Currently selected menu item ID, or zero if there is no menu.
     */
    var selectedItemId: Int
        get() = menuView.selectedItemId
        set(value) {
            val item = menu.findItem(value)
            if (item != null) {
                if (!menu.performItemAction(item, presenter, 0)) {
                    item.isChecked = true
                }
            }
        }

    /**
     * Current gravity setting for how destinations in the menu view will be grouped.
     */
    private var menuGravity: Int
        get() = menuView.menuGravity
        set(value) {
            menuView.menuGravity = value
        }

    /**
     * Current spacing setting for how destinations in the menu view will be spaced out.
     */
    private var menuSpacing: Int
        get() = menuView.menuSpacing
        set(value) {
            menuView.menuSpacing = value
        }


    init {
        val attributes = context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.NavigationSlideView,
            0,
            0
        )

        presenter.menuView = menuView
        menuView.presenter = presenter
        menu.addMenuPresenter(presenter)
        presenter.initForMenu(getContext(), menu)

        val headerLayoutRes = attributes.getResourceId(
            R.styleable.NavigationSlideView_headerLayout,
            0
        )
        if (headerLayoutRes != 0) {
            addHeaderView(headerLayoutRes)
        }

        menuGravity = attributes.getInt(
            R.styleable.NavigationSlideView_menuGravity,
            DEFAULT_MENU_GRAVITY
        )

        menuSpacing = attributes.getDimensionPixelSize(
            R.styleable.NavigationSlideView_menuSpacing,
            DEFAULT_MENU_SPACING
        )

        collapsedWidth = attributes.getDimensionPixelSize(
            R.styleable.NavigationSlideView_railCollapsedWidth,
            0
        )
        expandedWidth = attributes.getDimensionPixelSize(
            R.styleable.NavigationSlideView_railExpandedWidth,
            0
        )

        inflateMenu(attributes.getResourceIdOrThrow(R.styleable.NavigationSlideView_menu))

        attributes.recycle()

        addView(menuView)

        menu.setCallback(object : MenuBuilder.Callback {
            override fun onMenuItemSelected(menu: MenuBuilder, item: MenuItem): Boolean {
                if (reselectedListener != null && item.itemId == selectedItemId) {
                    reselectedListener?.invoke(item)
                    return true
                }
                return !(selectedListener?.invoke(item) ?: true)
            }

            override fun onMenuModeChange(menu: MenuBuilder) {}
        })
    }


    fun setOnItemSelectedListener(onNavigationItemSelected: (item: MenuItem) -> Boolean) {
        selectedListener = onNavigationItemSelected
    }

    fun setOnItemReselectedListener(onNavigationItemReselected: (item: MenuItem) -> Boolean) {
        reselectedListener = onNavigationItemReselected
    }

    /**
     * Inflate a menu resource into this navigation view.
     *
     *
     * Existing items in the menu will not be modified or removed.
     *
     * @param resId ID of a menu resource to inflate
     */
    fun inflateMenu(resId: Int) {
        presenter.updateSuspended = true
        menuInflater.inflate(resId, menu)
        presenter.updateSuspended = false
        presenter.updateMenuView(true)
    }

    fun buildNavigation() {
        headerView?.setOnFocusChangeListener { _, _ ->
            when {
                headerView?.hasFocus() == true || menuView.hasFocus() -> open()
                else -> close()
            }
        }
        menuView.forEach { child, item ->
            child.setOnFocusChangeListener { _, _ ->
                when {
                    headerView?.hasFocus() == true || menuView.hasFocus() -> open()
                    else -> close()
                }
            }

            child.setOnClickListener {
                if (!menu.performItemAction(item, presenter, 0)) {
                    item.isChecked = true
                }
            }
        }

        when {
            isOpen -> open()
            else -> close()
        }
    }

    fun addHeaderView(@LayoutRes layoutRes: Int) {
        val headerView = LayoutInflater.from(context).inflate(layoutRes, this, false)
        if (headerView is NavigationSlideHeaderView) {
            addHeaderView(headerView)
        }
    }

    fun addHeaderView(headerView: NavigationSlideHeaderView) {
        removeHeaderView()
        this.headerView = headerView
        addView(headerView, 0)
    }

    fun removeHeaderView() {
        if (headerView != null) {
            removeView(headerView)
            headerView = null
        }
    }

    fun open() {
        isOpen = true

        headerView?.open()
        menuView.open(animationsEnabled && isLaidOut)
        animateRailWidth()
    }

    fun close() {
        isOpen = false

        headerView?.close()
        menuView.close()
        animateRailWidth()
    }

    // Collapse once focus leaves the rail entirely (e.g. header avatar -> content), which the
    // per-item listeners miss.
    private val focusLeaveListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (isOpen && newFocus != null && !isDescendant(newFocus)) close()
    }

    private fun isDescendant(view: android.view.View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusLeaveListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusLeaveListener)
        widthAnimation?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!isRailMode) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        if (railWidth < 0f) railWidth = targetRailWidth().toFloat()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(railWidth.roundToInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
            heightMeasureSpec,
        )
    }

    private fun targetRailWidth() = if (isOpen) expandedWidth else collapsedWidth

    /**
     * Springs the rail width. Only this view re-measures each frame: the content beside it is
     * laid out against the collapsed width, so the expanded rail overlays it.
     */
    private fun animateRailWidth() {
        if (!isRailMode) return
        val target = targetRailWidth().toFloat()
        if (!animationsEnabled || !isLaidOut || railWidth < 0f) {
            widthAnimation?.cancel()
            railWidth = target
            requestLayout()
            return
        }
        val animation = widthAnimation ?: SpringAnimation(this, RAIL_WIDTH).also {
            it.setMinimumVisibleChange(1f)
            widthAnimation = it
        }
        animation.spring = (animation.spring ?: SpringForce()).apply {
            stiffness = widthStiffness
            dampingRatio = widthDamping
            finalPosition = target
        }
        animation.animateToFinalPosition(target)
    }


    companion object {
        private val RAIL_WIDTH = object : FloatPropertyCompat<NavigationSlideView>("railWidth") {
            override fun getValue(view: NavigationSlideView): Float = view.railWidth

            override fun setValue(view: NavigationSlideView, value: Float) {
                view.railWidth = value
                view.requestLayout()
            }
        }

        const val DEFAULT_MENU_GRAVITY = Gravity.TOP or Gravity.START
        const val DEFAULT_MENU_SPACING = 0
    }
}