package com.streamflixreborn.streamflix.fragments.queue

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.DialogQueueSwipeBinding
import com.streamflixreborn.streamflix.databinding.ItemQueueSwipeCardBinding
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Swipe through suggestions to build the Queue: right adds to the Watchlist, left skips (and
 * remembers it), up marks it seen.
 */
object QueueSwipeDialog {

    private enum class Decision { ADD, SKIP, SEEN }

    @SuppressLint("ClickableViewAccessibility")
    fun show(fragment: Fragment, viewModel: QueueViewModel) {
        val context = fragment.requireContext()
        val appContext = context.applicationContext
        val binding = DialogQueueSwipeBinding.inflate(LayoutInflater.from(context))
        val front = binding.cardQueueSwipeFront
        val back = binding.cardQueueSwipeBack
        val items = mutableListOf<AppAdapter.Item>()
        var index = 0
        var added = 0
        var animating = false


        fun bind(card: ItemQueueSwipeCardBinding, item: AppAdapter.Item?) {
            card.root.isVisible = item != null
            item ?: return
            val (title, poster, meta, overview) = when (item) {
                is Movie -> listOf(
                    item.title,
                    item.poster ?: item.banner,
                    listOfNotNull(context.getString(R.string.queue_kind_movie), item.released?.format("yyyy"), rating(item.rating)).joinToString(" · "),
                    item.overview,
                )
                is TvShow -> listOf(
                    item.title,
                    item.poster ?: item.banner,
                    listOfNotNull(context.getString(R.string.queue_kind_show), item.released?.format("yyyy"), rating(item.rating)).joinToString(" · "),
                    item.overview,
                )
                else -> listOf(null, null, null, null)
            }
            card.tvSwipeTitle.text = title
            card.tvSwipeMeta.text = meta
            card.tvSwipeOverview.text = overview
            card.tvSwipeOverview.isVisible = !overview.isNullOrBlank()
            Glide.with(card.ivSwipePoster).load(poster).centerCrop().into(card.ivSwipePoster)
            card.tvSwipeStampAdd.alpha = 0f
            card.tvSwipeStampSkip.alpha = 0f
            card.tvSwipeStampSeen.alpha = 0f
        }

        fun resetFront() {
            front.root.animate().cancel()
            front.root.translationX = 0f
            front.root.translationY = 0f
            front.root.rotation = 0f
            front.root.alpha = 1f
            back.root.scaleX = 0.92f
            back.root.scaleY = 0.92f
            back.root.translationY = 24f * context.resources.displayMetrics.density
        }

        fun showCurrent() {
            val current = items.getOrNull(index)
            bind(front, current)
            bind(back, items.getOrNull(index + 1))
            resetFront()
            val empty = current == null
            binding.tvQueueSwipeEmpty.isVisible = empty && !binding.llQueueSwipeLoading.isVisible
            binding.btnQueueSwipeAdd.isEnabled = !empty
            binding.btnQueueSwipeSkip.isEnabled = !empty
            binding.btnQueueSwipeSeen.isEnabled = !empty
        }

        fun updateStamps(dx: Float, dy: Float) {
            val width = front.root.width.coerceAtLeast(1).toFloat()
            front.tvSwipeStampAdd.alpha = (dx / (width * 0.35f)).coerceIn(0f, 1f)
            front.tvSwipeStampSkip.alpha = (-dx / (width * 0.35f)).coerceIn(0f, 1f)
            front.tvSwipeStampSeen.alpha = if (abs(dx) < abs(dy)) (-dy / (width * 0.45f)).coerceIn(0f, 1f) else 0f
        }

        fun decide(decision: Decision) {
            if (animating) return
            val item = items.getOrNull(index) ?: return
            animating = true
            front.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            when (decision) {
                Decision.ADD -> {
                    viewModel.addFromSwipe(appContext, item, seen = false)
                    added++
                    binding.tvQueueSwipeCount.text = context.getString(R.string.queue_swipe_added, added)
                    binding.tvQueueSwipeCount.visibility = View.VISIBLE
                }
                Decision.SEEN -> viewModel.addFromSwipe(appContext, item, seen = true)
                Decision.SKIP -> QueueSwipeMemory.skip(appContext, viewModel.keyOf(item))
            }
            val width = front.root.width.toFloat().coerceAtLeast(1f)
            val (tx, ty, rot) = when (decision) {
                Decision.ADD -> Triple(width * 1.6f, front.root.translationY, 24f)
                Decision.SKIP -> Triple(-width * 1.6f, front.root.translationY, -24f)
                Decision.SEEN -> Triple(front.root.translationX, -front.root.height * 1.4f, 0f)
            }
            updateStamps(if (decision == Decision.ADD) width else if (decision == Decision.SKIP) -width else 0f, if (decision == Decision.SEEN) -width else 0f)
            back.root.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(260).start()
            front.root.animate()
                .translationX(tx).translationY(ty).rotation(rot).alpha(0.4f)
                .setDuration(260)
                .setInterpolator(PathInterpolator(0.3f, 0f, 0.8f, 0.15f))
                .withEndAction {
                    index++
                    animating = false
                    showCurrent()
                }
                .start()
        }

        // Drag the front card; let go past a third of its width (or up past half) to decide.
        var downX = 0f
        var downY = 0f
        front.root.setOnTouchListener { v, event ->
            if (animating) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    v.translationX = dx
                    v.translationY = dy
                    v.rotation = dx / v.width.coerceAtLeast(1) * 18f
                    updateStamps(dx, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    when {
                        dx > v.width * 0.33f -> decide(Decision.ADD)
                        dx < -v.width * 0.33f -> decide(Decision.SKIP)
                        dy < -v.height * 0.3f && abs(dy) > abs(dx) -> decide(Decision.SEEN)
                        else -> {
                            v.animate().translationX(0f).translationY(0f).rotation(0f)
                                .setInterpolator(OvershootInterpolator(1.4f)).setDuration(320).start()
                            updateStamps(0f, 0f)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        binding.btnQueueSwipeAdd.setOnClickListener { decide(Decision.ADD) }
        binding.btnQueueSwipeSkip.setOnClickListener { decide(Decision.SKIP) }
        binding.btnQueueSwipeSeen.setOnClickListener { decide(Decision.SEEN) }

        val dialog = BottomSheetDialog(context).apply {
            setContentView(binding.root)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            // Dragging a card must not drag the sheet.
            behavior.isDraggable = false
        }

        binding.llQueueSwipeLoading.isVisible = true
        showCurrent()
        dialog.show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val candidates = viewModel.swipeCandidates(appContext)
            items.clear()
            items.addAll(candidates)
            index = 0
            binding.llQueueSwipeLoading.isVisible = false
            showCurrent()
        }
    }

    private fun rating(value: Double?): String? = value?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }
}
