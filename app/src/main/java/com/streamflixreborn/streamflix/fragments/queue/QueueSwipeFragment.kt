package com.streamflixreborn.streamflix.fragments.queue

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentQueueSwipeBinding
import com.streamflixreborn.streamflix.databinding.ItemQueueSwipeCardBinding
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.ReleaseStatus
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Swipe to build the Queue, as a whole page: right adds to the Watchlist, left skips (and remembers
 * it), up marks it seen. Tapping the poster or the info panel opens the title's page; coming back
 * resumes on the same card.
 */
class QueueSwipeFragment : Fragment() {

    private var _binding: FragmentQueueSwipeBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModelsFactory { QueueSwipeViewModel(AppDatabase.getInstance(requireContext())) }

    private var animating = false
    private var shownKey: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQueueSwipeBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnSwipeBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnQueueSwipeAdd.setOnClickListener { decide(SwipeDecision.ADD) }
        binding.btnQueueSwipeSkip.setOnClickListener { decide(SwipeDecision.SKIP) }
        binding.btnQueueSwipeSeen.setOnClickListener { decide(SwipeDecision.SEEN) }
        binding.cardSwipeInfo.setOnClickListener { openCurrentDetails() }

        // Size the cards to the biggest 2:3 poster that fits the stack.
        binding.flQueueSwipeStack.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizeCards() }
        sizeCards()
        attachDrag(binding.cardQueueSwipeFront)

        viewModel.load(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect(::render)
        }
    }

    private fun sizeCards() {
        val stack = binding.flQueueSwipeStack
        if (stack.width == 0 || stack.height == 0) return
        val gutter = resources.getDimensionPixelSize(R.dimen.browse_gutter)
        val maxWidth = stack.width - 2 * gutter
        val maxHeight = stack.height - resources.displayMetrics.density.times(16).toInt()
        val height = min(maxHeight, maxWidth * 3 / 2)
        val width = height * 2 / 3
        for (card in listOf(binding.cardQueueSwipeBack.root, binding.cardQueueSwipeFront.root)) {
            val params = (card.layoutParams as FrameLayout.LayoutParams)
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                params.gravity = android.view.Gravity.CENTER
                card.layoutParams = params
            }
        }
    }

    private fun render(state: QueueSwipeViewModel.UiState) {
        val current = state.current
        binding.llQueueSwipeLoading.isVisible = state.loading
        binding.tvQueueSwipeEmpty.isVisible = !state.loading && current == null
        binding.cardSwipeInfo.isVisible = current != null
        val actionsEnabled = current != null
        binding.btnQueueSwipeAdd.isEnabled = actionsEnabled
        binding.btnQueueSwipeSkip.isEnabled = actionsEnabled
        binding.btnQueueSwipeSeen.isEnabled = actionsEnabled
        binding.tvSwipeCount.isVisible = state.added > 0
        binding.tvSwipeCount.text = getString(R.string.queue_swipe_added, state.added)

        val key = current?.let { QueueSwipeViewModel.keyOf(it) }
        if (animating && key != shownKey) return // the card is flying off; the next bind happens when it lands
        bind(binding.cardQueueSwipeFront, current)
        bind(binding.cardQueueSwipeBack, state.next)
        bindInfo(current)
        resetFront()
        shownKey = key
    }

    private fun bind(card: ItemQueueSwipeCardBinding, item: AppAdapter.Item?) {
        card.root.isVisible = item != null
        item ?: return
        Glide.with(card.ivSwipePoster).load(QueueSwipeViewModel.posterOf(item)).centerCrop().into(card.ivSwipePoster)
        val released = when (item) {
            is Movie -> item.released
            is TvShow -> item.released
            else -> null
        }
        if (released != null && ReleaseStatus.isUnreleased(released)) {
            card.tvSwipeSoon.text = getString(R.string.browse_coming_soon, ReleaseStatus.shortDate(requireContext(), released))
            card.tvSwipeSoon.isVisible = true
        } else {
            card.tvSwipeSoon.isVisible = false
        }
        card.tvSwipeStampAdd.alpha = 0f
        card.tvSwipeStampSkip.alpha = 0f
        card.tvSwipeStampSeen.alpha = 0f
    }

    private fun bindInfo(item: AppAdapter.Item?) {
        val context = requireContext()
        val (title, meta, overview) = when (item) {
            is Movie -> Triple(
                item.title,
                listOfNotNull(
                    context.getString(R.string.queue_kind_movie),
                    item.released?.format("yyyy"),
                    item.genres.take(2).joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
                    rating(item.rating),
                ).joinToString(" · "),
                item.overview,
            )

            is TvShow -> Triple(
                item.title,
                listOfNotNull(
                    context.getString(R.string.queue_kind_show),
                    item.released?.format("yyyy"),
                    item.genres.take(2).joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
                    rating(item.rating),
                ).joinToString(" · "),
                item.overview,
            )

            else -> Triple("", "", null)
        }
        binding.tvSwipeInfoTitle.text = title
        binding.tvSwipeInfoMeta.text = meta
        binding.tvSwipeInfoOverview.text = overview
        binding.tvSwipeInfoOverview.isVisible = !overview.isNullOrBlank()
    }

    private fun resetFront() {
        val front = binding.cardQueueSwipeFront.root
        front.animate().cancel()
        front.translationX = 0f
        front.translationY = 0f
        front.rotation = 0f
        front.alpha = 1f
        val back = binding.cardQueueSwipeBack.root
        back.animate().cancel()
        back.scaleX = 0.92f
        back.scaleY = 0.92f
        back.translationY = 24f * resources.displayMetrics.density
    }

    private fun updateStamps(dx: Float, dy: Float) {
        val front = binding.cardQueueSwipeFront
        val width = front.root.width.coerceAtLeast(1).toFloat()
        front.tvSwipeStampAdd.alpha = (dx / (width * 0.35f)).coerceIn(0f, 1f)
        front.tvSwipeStampSkip.alpha = (-dx / (width * 0.35f)).coerceIn(0f, 1f)
        front.tvSwipeStampSeen.alpha = if (abs(dx) < abs(dy)) (-dy / (width * 0.45f)).coerceIn(0f, 1f) else 0f
    }

    private fun decide(decision: SwipeDecision) {
        if (animating || viewModel.state.value.current == null) return
        animating = true
        val front = binding.cardQueueSwipeFront.root
        front.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val width = front.width.toFloat().coerceAtLeast(1f)
        val (tx, ty, rot) = when (decision) {
            SwipeDecision.ADD -> Triple(width * 1.6f, front.translationY, 24f)
            SwipeDecision.SKIP -> Triple(-width * 1.6f, front.translationY, -24f)
            SwipeDecision.SEEN -> Triple(front.translationX, -front.height * 1.4f, 0f)
        }
        updateStamps(
            if (decision == SwipeDecision.ADD) width else if (decision == SwipeDecision.SKIP) -width else 0f,
            if (decision == SwipeDecision.SEEN) -width else 0f,
        )
        binding.cardQueueSwipeBack.root.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(260).start()
        front.animate()
            .translationX(tx).translationY(ty).rotation(rot).alpha(0.4f)
            .setDuration(260)
            .setInterpolator(PathInterpolator(0.3f, 0f, 0.8f, 0.15f))
            .withEndAction {
                animating = false
                if (_binding == null) return@withEndAction
                viewModel.decide(requireContext(), decision)
            }
            .start()
    }

    /** Drag the front card; let go past a third of its width (or up past a third of its height) to decide. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(card: ItemQueueSwipeCardBinding) {
        val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        card.root.setOnTouchListener { v, event ->
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
                        event.actionMasked == MotionEvent.ACTION_UP && abs(dx) < slop && abs(dy) < slop -> {
                            v.performClick()
                            openCurrentDetails()
                        }

                        dx > v.width * 0.33f -> decide(SwipeDecision.ADD)
                        dx < -v.width * 0.33f -> decide(SwipeDecision.SKIP)
                        dy < -v.height * 0.3f && abs(dy) > abs(dx) -> decide(SwipeDecision.SEEN)
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
    }

    private fun openCurrentDetails() {
        when (val item = viewModel.state.value.current) {
            is Movie -> QueueNavigator.openDetails(findNavController(), item.id, false, item.poster, item.banner)
            is TvShow -> QueueNavigator.openDetails(findNavController(), item.id, true, item.poster, item.banner)
            else -> Unit
        }
    }

    private fun rating(value: Double?): String? = value?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
