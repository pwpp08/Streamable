package com.streamflixreborn.streamflix.downloads

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ViewDownloadButtonBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Download control for a movie or an episode: start, progress ring, and a menu for pause /
 * resume / cancel / delete / "Try another server". Hidden on TV.
 */
class DownloadButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val binding = ViewDownloadButtonBinding.inflate(LayoutInflater.from(context), this)
    private val supported = DownloadUtil.isSupported(context)

    private var target: DownloadStarter.Target? = null
    private var item: DownloadTracker.Item? = null
    private var scope: CoroutineScope? = null

    /** Shows the state text next to the icon (movie details). */
    var showLabel: Boolean = false
        set(value) {
            field = value
            render(item)
        }

    /** Runs before a download starts, e.g. switching to the title's provider. */
    var beforeStart: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        TypedValue().also {
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)) {
                setBackgroundResource(it.resourceId)
            }
        }
        setOnClickListener { onClicked() }
        visibility = if (supported) View.VISIBLE else View.GONE
    }

    fun bind(target: DownloadStarter.Target?) {
        this.target = target
        if (!supported || target == null) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        DownloadTracker.ensureStarted(context)
        render(DownloadTracker.item(target.id))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!supported) return
        scope?.cancel()
        scope = MainScope().also { scope ->
            scope.launch {
                DownloadTracker.items.collect { items ->
                    val id = target?.id ?: return@collect
                    val next = items[id]
                    if (next != item) render(next)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    private fun onClicked() {
        val target = target ?: return
        val current = item
        when (current?.status) {
            null, DownloadTracker.Status.NONE -> {
                DownloadActions.requestNotificationPermission(DownloadActions.activityOf(context))
                beforeStart?.invoke()
                DownloadStarter.start(context, target)
            }
            DownloadTracker.Status.RESOLVING,
            DownloadTracker.Status.REMOVING ->
                Toast.makeText(context, DownloadActions.statusText(context, current), Toast.LENGTH_SHORT).show()
            else -> DownloadActions.showMenu(this, current)
        }
    }

    private fun render(item: DownloadTracker.Item?) {
        this.item = item
        val status = item?.status ?: DownloadTracker.Status.NONE
        val ring = binding.pbDownloadRing
        val spinner = binding.pbDownloadSpinner
        val icon = binding.ivDownloadIcon

        val hasPercent = (item?.percent ?: -1) >= 0
        val showRing = (status == DownloadTracker.Status.DOWNLOADING || status == DownloadTracker.Status.PAUSED) && hasPercent
        val showSpinner = status == DownloadTracker.Status.RESOLVING ||
            status == DownloadTracker.Status.REMOVING ||
            (status == DownloadTracker.Status.DOWNLOADING && !hasPercent)
        ring.visibility = if (showRing) View.VISIBLE else View.GONE
        if (showRing) ring.progress = item?.percent ?: 0
        spinner.visibility = if (showSpinner) View.VISIBLE else View.GONE

        val iconRes = when (status) {
            DownloadTracker.Status.NONE -> R.drawable.ic_download
            DownloadTracker.Status.QUEUED,
            DownloadTracker.Status.WAITING -> R.drawable.ic_download_queued
            DownloadTracker.Status.DOWNLOADING -> R.drawable.ic_download_pause
            DownloadTracker.Status.PAUSED -> R.drawable.ic_download_resume
            DownloadTracker.Status.COMPLETED -> R.drawable.ic_download_done
            DownloadTracker.Status.FAILED -> R.drawable.ic_download_error
            DownloadTracker.Status.RESOLVING,
            DownloadTracker.Status.REMOVING -> null
        }
        if (iconRes != null) {
            icon.setImageResource(iconRes)
            icon.visibility = View.VISIBLE
            val small = status == DownloadTracker.Status.DOWNLOADING || status == DownloadTracker.Status.PAUSED
            val size = ((if (small) 14 else 20) * resources.displayMetrics.density).toInt()
            icon.layoutParams = icon.layoutParams.apply { width = size; height = size }
        } else {
            icon.visibility = View.GONE
        }

        val text = DownloadActions.statusText(context, item)
        contentDescription = text
        binding.tvDownloadLabel.text = text
        binding.tvDownloadLabel.visibility = if (showLabel) View.VISIBLE else View.GONE
    }
}
