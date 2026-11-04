package com.streamflixreborn.streamflix.fragments.settings.homerows

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.ui.tvFocusSpring
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.databinding.FragmentHomeRowsBinding
import com.streamflixreborn.streamflix.fragments.home.HomeRowsConfig
import com.streamflixreborn.streamflix.fragments.home.HomeRowsStore
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.utils.HomeCacheStore
import com.streamflixreborn.streamflix.utils.HomeBanner
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Home screen: order and visibility of the Home rows, shared by the phone and TV
 * navigation graphs. Provider rows come from the last cached Home of the current provider.
 */
class HomeRowsFragment : Fragment() {

    private var _binding: FragmentHomeRowsBinding? = null
    private val binding get() = _binding!!
    private val isTv get() = activity is MainTvActivity

    private var providerCategories: List<String> = emptyList()
    private lateinit var adapter: HomeRowsAdapter
    private lateinit var touchHelper: ItemTouchHelper

    /** What the home screen currently uses; the adapter holds the staged edits. */
    private var baseline: HomeRowsConfig? = null
    private var banner: String = UserPreferences.homeBanner
    private var bannerBaseline: String = banner
    private val dirty get() = baseline != null && (adapter.config != baseline || banner != bannerBaseline)

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.home_rows_unsaved_title)
                .setMessage(R.string.home_rows_unsaved_message)
                .setPositiveButton(R.string.home_rows_save) { _, _ -> save(); leave() }
                .setNegativeButton(R.string.home_rows_discard) { _, _ -> baseline = adapter.config; leave() }
                .setNeutralButton(R.string.home_rows_keep_editing, null)
                .show()
        }
    }

    private fun bannerLabel(value: String): String = when {
        value == HomeBanner.OFF -> getString(R.string.home_banner_off)
        value == HomeBanner.FAVORITES -> getString(R.string.home_banner_favorites)
        value == HomeBanner.RECENT -> getString(R.string.home_banner_recent)
        HomeBanner.rowName(value) != null -> HomeBanner.rowName(value)!!
        else -> getString(R.string.home_banner_trending)
    }

    private fun updateBannerButton() {
        _binding?.btnHomeBanner?.text = getString(R.string.home_banner_current, bannerLabel(banner))
    }

    /** Lets the user pick what the big carousel on top of Home shows (applied on Save). */
    private fun chooseBanner() {
        val values = listOf(HomeBanner.TRENDING, HomeBanner.FAVORITES, HomeBanner.RECENT) +
            providerCategories.filter { it != Category.FEATURED }.map(HomeBanner::row) +
            listOf(HomeBanner.OFF)
        val checked = values.indexOf(banner).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_banner_title)
            .setSingleChoiceItems(values.map(::bannerLabel).toTypedArray(), checked) { dialog, which ->
                banner = values[which]
                updateBannerButton()
                updateButtons()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateButtons() {
        val b = _binding ?: return
        val changed = dirty
        b.btnHomeRowsSave.isEnabled = changed
        // Solid colours in both states (a faded button lets the rows underneath show through).
        val ctx = b.root.context
        val container = com.google.android.material.color.MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, 0)
        val disabled = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainerHighest, 0)
        b.btnHomeRowsSave.backgroundTintList = android.content.res.ColorStateList.valueOf(if (changed) container else disabled)
        b.btnHomeRowsSave.setTextColor(com.google.android.material.color.MaterialColors.getColor(ctx, if (changed) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
        backCallback.isEnabled = changed
    }

    private fun save() {
        val config = adapter.config
        if (banner != bannerBaseline) UserPreferences.homeBanner = banner
        if (config == HomeRowsConfig.default(providerCategories)) HomeRowsStore.reset() else HomeRowsStore.save(config)
        bannerBaseline = banner
        baseline = config
        updateButtons()
        Toast.makeText(requireContext(), R.string.home_rows_saved, Toast.LENGTH_SHORT).show()
    }

    /** Leaves the screen without asking again. */
    private fun leave() {
        backCallback.isEnabled = false
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeRowsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.title = getString(R.string.home_rows_title)
        val context = requireContext()

        if (isTv) {
            binding.homeRowsRoot.setPadding(48.dp(context), 27.dp(context), 48.dp(context), 27.dp(context))
        }
        binding.tvHomeRowsHint.setText(if (isTv) R.string.home_rows_hint_tv else R.string.home_rows_hint_mobile)

        val providerName = UserPreferences.currentProvider?.name.orEmpty()
        adapter = HomeRowsAdapter(
            isTv = isTv,
            titleOf = ::titleOf,
            subtitleOf = { key ->
                if (HomeRowsConfig.isProviderKey(key)) getString(R.string.home_rows_provider_row, providerName)
                else getString(R.string.home_rows_library_row)
            },
            onStartDrag = { holder -> touchHelper.startDrag(holder) },
            onChanged = { updateButtons() },
        )

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = !isTv

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                updateButtons()
            }
        })

        binding.rvHomeRows.layoutManager = LinearLayoutManager(context)
        binding.rvHomeRows.adapter = adapter
        // Keep the same holder on change so the focused TV button stays focused.
        (binding.rvHomeRows.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        if (!isTv) touchHelper.attachToRecyclerView(binding.rvHomeRows)

        if (isTv) listOf(binding.btnHomeRowsSave, binding.btnHomeRowsCancel, binding.btnHomeRowsReset).forEach { it.tvFocusSpring() }
        // Edits are staged: nothing reaches the home screen until Save.
        binding.btnHomeRowsReset.setOnClickListener {
            banner = HomeBanner.TRENDING
            updateBannerButton()
            adapter.submit(HomeRowsConfig.default(providerCategories))
            updateButtons()
            Toast.makeText(context, R.string.home_rows_reset_done, Toast.LENGTH_SHORT).show()
        }
        updateBannerButton()
        if (isTv) binding.btnHomeBanner.tvFocusSpring()
        binding.btnHomeBanner.setOnClickListener { chooseBanner() }
        binding.btnHomeRowsSave.setOnClickListener { save(); leave() }
        binding.btnHomeRowsCancel.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        viewLifecycleOwner.lifecycleScope.launch {
            providerCategories = withContext(Dispatchers.IO) {
                val provider = UserPreferences.currentProvider ?: return@withContext emptyList()
                HomeCacheStore.read(context.applicationContext, provider).orEmpty()
                    .map { it.name }
                    .filter { it != Category.FEATURED }
            }
            val b = _binding ?: return@launch
            b.tvHomeRowsNote.visibility = if (providerCategories.isEmpty()) View.VISIBLE else View.GONE
            val config = (HomeRowsStore.load() ?: HomeRowsConfig.default(providerCategories)).merge(providerCategories)
            adapter.submit(config)
            baseline = adapter.config
            updateButtons()
            if (isTv) b.rvHomeRows.post { _binding?.rvHomeRows?.requestFocus() }
        }
    }

    private fun titleOf(key: String): String = when (key) {
        HomeRowsConfig.CONTINUE_WATCHING -> getString(R.string.home_continue_watching)
        HomeRowsConfig.RECENTLY_WATCHED -> getString(R.string.home_recently_watched)
        HomeRowsConfig.FAVORITE_MOVIES -> getString(R.string.home_favorite_movies)
        HomeRowsConfig.FAVORITE_TV_SHOWS -> getString(R.string.home_favorite_tv_shows)
        else -> HomeRowsConfig.providerCategoryName(key) ?: key
    }

    override fun onDestroyView() {
        binding.rvHomeRows.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
