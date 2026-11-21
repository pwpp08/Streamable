package com.streamflixreborn.streamflix.fragments.torrents

import android.app.Dialog
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.streamflixreborn.streamflix.NavMainGraphMobileDirections
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.ItemTorrentOptionBinding
import com.streamflixreborn.streamflix.databinding.SheetTorrentPickerBinding
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.torrent.TorrentBrowser
import com.streamflixreborn.streamflix.torrent.TorrentChoice
import com.streamflixreborn.streamflix.torrent.TorrentFilter
import com.streamflixreborn.streamflix.torrent.TorrentOption
import com.streamflixreborn.streamflix.torrent.TorrentSort
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Watch with torrents": every torrent Torrentio and the viewer's addons (MediaFusion) have for a
 * movie or an episode, with filters for resolution, source, codec, HDR, seeders and size.
 * Tapping one plays it.
 */
class TorrentPickerSheet : BottomSheetDialogFragment() {

    private var _binding: SheetTorrentPickerBinding? = null
    private val binding get() = _binding!!

    private val item: AppAdapter.Item? = pending
    private var all: List<TorrentOption> = emptyList()
    private var filter = TorrentFilter()
    private var sort = TorrentSort.SEEDERS
    private var loadJob: Job? = null

    private var season: com.streamflixreborn.streamflix.models.Season? = null
    private var episode: Episode? = null
    private var episodes: List<Episode> = emptyList()

    private val adapter = OptionAdapter { play(it) }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetTorrentPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val target = item
        if (target == null) {
            dismissAllowingStateLoss()
            return
        }
        binding.btnTorrentClose.setOnClickListener { dismiss() }
        binding.btnTorrentFilters.setOnClickListener { binding.llTorrentFilters.isVisible = !binding.llTorrentFilters.isVisible }
        binding.rvTorrents.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTorrents.adapter = adapter
        binding.tvTorrentTitle.text = when (target) {
            is Movie -> listOfNotNull(target.title, target.released?.format("yyyy")).joinToString(" · ")
            is TvShow -> target.title
            else -> ""
        }
        buildFilters()
        when (target) {
            is Movie -> load()
            is TvShow -> {
                binding.llTorrentEpisodes.isVisible = true
                loadSeasons(target)
            }
            else -> dismissAllowingStateLoss()
        }
    }

    // ---- Episode choice (series) ---------------------------------------------------------------

    private fun loadSeasons(show: TvShow) {
        val seasons = show.seasons.filter { it.number > 0 }.ifEmpty { show.seasons }
        if (seasons.isEmpty()) {
            showStatus(getString(R.string.torrents_none))
            return
        }
        val chips = chipRow(binding.llTorrentEpisodes, getString(R.string.torrents_season), seasons.map { it.number.toString() to it }, singleSelect = true) { picked ->
            season = picked
            loadEpisodes(show, picked)
        }
        // Start where the viewer left off, else at the first season.
        val start = show.seasons.firstOrNull { it.number == 1 } ?: seasons.first()
        chips.checkIndex(seasons.indexOf(start).coerceAtLeast(0))
    }

    private var episodeRow: ChipGroup? = null

    private fun loadEpisodes(show: TvShow, season: com.streamflixreborn.streamflix.models.Season) {
        loadJob?.cancel()
        episodes = emptyList()
        episode = null
        all = emptyList()
        render()
        showStatus(getString(R.string.torrents_loading))
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val list = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    UserPreferences.currentProvider!!.getEpisodesBySeason(season.id)
                }
            }.getOrDefault(emptyList())
            if (_binding == null) return@launch
            episodes = list.sortedBy { it.number }
            episodeRow?.let { ((it.parent as? View)?.parent as? View)?.let { row -> binding.llTorrentEpisodes.removeView(row) } }
            if (episodes.isEmpty()) {
                showStatus(getString(R.string.torrents_none))
                return@launch
            }
            val group = chipRow(binding.llTorrentEpisodes, getString(R.string.torrents_episode), episodes.map { it.number.toString() to it }, singleSelect = true) { picked ->
                episode = picked
                load()
            }
            episodeRow = group.group
            group.checkIndex(0)
        }
    }

    // ---- Loading -------------------------------------------------------------------------------

    private fun videoType(): Video.Type? = when (val target = item) {
        is Movie -> Video.Type.Movie(
            id = target.id,
            title = target.title,
            releaseDate = target.released?.format("yyyy-MM-dd") ?: "",
            poster = target.poster ?: "",
            imdbId = target.imdbId,
        )
        is TvShow -> {
            val ep = episode
            val se = season
            if (ep == null || se == null) null else Video.Type.Episode(
                id = ep.id,
                number = ep.number,
                title = ep.title,
                poster = ep.poster,
                overview = ep.overview,
                tvShow = Video.Type.Episode.TvShow(
                    id = target.id,
                    title = target.title,
                    poster = target.poster,
                    banner = target.banner,
                    releaseDate = target.released?.format("yyyy-MM-dd"),
                    imdbId = target.imdbId,
                ),
                season = Video.Type.Episode.Season(number = se.number, title = se.title),
            )
        }
        else -> null
    }

    private fun load() {
        val type = videoType() ?: return
        loadJob?.cancel()
        all = emptyList()
        render()
        showStatus(getString(R.string.torrents_loading))
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val found = runCatching { TorrentBrowser.load(type) }.getOrDefault(emptyList())
            if (_binding == null) return@launch
            all = found
            render()
        }
    }

    // ---- Filters -------------------------------------------------------------------------------

    private class Row<T>(val group: ChipGroup, private val values: List<T>, private val onPick: (T) -> Unit) {
        fun checkIndex(index: Int) {
            (group.getChildAt(index) as? Chip)?.isChecked = true
            values.getOrNull(index)?.let(onPick)
        }
    }

    /** A labelled, horizontally scrolling row of chips. Single-select rows always keep one chosen. */
    private fun <T> chipRow(
        into: LinearLayout,
        label: String,
        entries: List<Pair<String, T>>,
        singleSelect: Boolean,
        multi: Boolean = false,
        onPick: (T) -> Unit,
    ): Row<T> {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (6 * density).toInt() }
        }
        container.addView(TextView(context).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setTextColor(com.streamflixreborn.streamflix.utils.ThemeManager.color(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        val group = ChipGroup(context).apply {
            isSingleLine = true
            isSingleSelection = singleSelect
            isSelectionRequired = singleSelect
        }
        entries.forEach { (text, value) ->
            group.addView(
                Chip(ContextThemeWrapper(context, R.style.Widget_StreamFlix_Queue_FilterChip), null, 0).apply {
                    id = View.generateViewId()
                    this.text = text
                    isCheckable = true
                    if (singleSelect) setOnClickListener { onPick(value) }
                    else setOnCheckedChangeListener { _, _ -> onPick(value) }
                },
            )
        }
        container.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(group)
        })
        into.addView(container)
        return Row(group, entries.map { it.second }, onPick)
    }

    private fun buildFilters() {
        val host = binding.llTorrentFilters
        val res = TorrentBrowser.RESOLUTIONS
        val resRow = chipRow(host, getString(R.string.torrents_resolution), res.map { (if (it == 2160) "4K" else if (it == 480) "SD" else "${it}p") to it }, singleSelect = false) { _ -> readChips() }
        val srcRow = chipRow(host, getString(R.string.torrents_source), TorrentBrowser.SOURCES.map { it to it }, singleSelect = false) { _ -> readChips() }
        val codecRow = chipRow(host, getString(R.string.torrents_codec), TorrentBrowser.CODECS.map { it to it } + (getString(R.string.torrents_hdr) to "HDR"), singleSelect = false) { _ -> readChips() }
        val seeders = listOf(0, 5, 20, 100)
        val seedRow = chipRow(host, getString(R.string.torrents_seeders), seeders.map { (if (it == 0) getString(R.string.torrents_any) else "$it+") to it }, singleSelect = true) { value ->
            filter = filter.copy(minSeeders = value)
            render()
        }
        val sizes = listOf<Double?>(null, 2.0, 5.0, 10.0, 20.0)
        val sizeRow = chipRow(host, getString(R.string.torrents_size), sizes.map { (if (it == null) getString(R.string.torrents_any) else "≤ ${it.toInt()} GB") to it }, singleSelect = true) { value ->
            filter = filter.copy(maxSizeGb = value)
            render()
        }
        val sortRow = chipRow(
            host,
            getString(R.string.torrents_sort),
            listOf(getString(R.string.torrents_sort_seeders) to TorrentSort.SEEDERS, getString(R.string.torrents_sort_quality) to TorrentSort.QUALITY, getString(R.string.torrents_sort_size) to TorrentSort.SIZE),
            singleSelect = true,
        ) { value ->
            sort = value
            render()
        }
        chipGroups = listOf(resRow.group, srcRow.group, codecRow.group)
        seedRow.group.let { (it.getChildAt(0) as? Chip)?.isChecked = true }
        sizeRow.group.let { (it.getChildAt(0) as? Chip)?.isChecked = true }
        sortRow.group.let { (it.getChildAt(0) as? Chip)?.isChecked = true }
    }

    private var chipGroups: List<ChipGroup> = emptyList()

    private fun checked(group: ChipGroup): List<String> =
        group.children().filterIsInstance<Chip>().filter { it.isChecked }.map { it.text.toString() }

    private fun ViewGroup.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    private fun readChips() {
        if (chipGroups.size < 3) return
        val resLabels = checked(chipGroups[0])
        val resolutions = resLabels.mapNotNull {
            when (it) {
                "4K" -> 2160
                "SD" -> 480
                else -> it.removeSuffix("p").toIntOrNull()
            }
        }.toSet()
        val codecLabels = checked(chipGroups[2])
        filter = filter.copy(
            resolutions = resolutions,
            sources = checked(chipGroups[1]).toSet(),
            codecs = codecLabels.filter { it in TorrentBrowser.CODECS }.toSet(),
            hdrOnly = codecLabels.any { it == getString(R.string.torrents_hdr) },
        )
        render()
    }

    // ---- List ----------------------------------------------------------------------------------

    private fun showStatus(text: String) {
        binding.tvTorrentStatus.text = text
        binding.tvTorrentStatus.isVisible = true
        binding.pbTorrents.isVisible = text == getString(R.string.torrents_loading)
    }

    private fun render() {
        if (_binding == null) return
        val shown = TorrentBrowser.sort(TorrentBrowser.filter(all, filter), sort)
        adapter.submitList(shown)
        when {
            binding.pbTorrents.isVisible && all.isEmpty() -> Unit
            all.isEmpty() -> showStatus(getString(R.string.torrents_none))
            shown.isEmpty() -> showStatus(getString(R.string.torrents_none_filtered))
            else -> {
                binding.pbTorrents.isVisible = false
                binding.tvTorrentStatus.text = getString(R.string.torrents_count, shown.size, all.size)
            }
        }
    }

    private fun play(option: TorrentOption) {
        val type = videoType() ?: return
        val target = item ?: return
        val server = TorrentBrowser.toServer(option)
        val playerId = when (type) {
            is Video.Type.Movie -> type.id
            is Video.Type.Episode -> type.id
        }
        TorrentChoice.set(playerId, server)
        val activity: FragmentActivity = requireActivity()
        val title: String
        val subtitle: String
        when (type) {
            is Video.Type.Movie -> {
                title = type.title
                subtitle = (target as? Movie)?.released?.format("yyyy") ?: ""
            }
            is Video.Type.Episode -> {
                title = type.tvShow.title
                subtitle = getString(
                    R.string.player_subtitle_tv_show,
                    type.season.number,
                    type.number,
                    type.title ?: getString(R.string.episode_number, type.number),
                )
            }
        }
        dismiss()
        activity.findNavController(R.id.nav_main_fragment).navigate(
            NavMainGraphMobileDirections.actionGlobalPlayer(
                id = playerId,
                title = title,
                subtitle = subtitle,
                videoType = type,
            ),
        )
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    private class OptionAdapter(private val onClick: (TorrentOption) -> Unit) :
        ListAdapter<TorrentOption, OptionAdapter.Holder>(Diff) {

        object Diff : DiffUtil.ItemCallback<TorrentOption>() {
            override fun areItemsTheSame(a: TorrentOption, b: TorrentOption) = a.stream.infoHash == b.stream.infoHash && a.stream.fileIdx == b.stream.fileIdx
            override fun areContentsTheSame(a: TorrentOption, b: TorrentOption) = a == b
        }

        class Holder(val binding: ItemTorrentOptionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTorrentOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val o = getItem(position)
            val b = holder.binding
            b.tvTorrentRelease.text = o.title
            b.tvTorrentBadge.text = when (o.resolution) {
                2160 -> "4K"
                480 -> "SD"
                else -> "${o.resolution}p"
            }
            b.tvTorrentMeta.text = buildList {
                o.source?.let { add(it) }
                o.codec?.let { add(it) }
                if (o.hdr) add("HDR")
                o.audio?.let { add(it) }
                if (o.stream.sizeBytes > 0) add(String.format(java.util.Locale.US, "%.1f GB", o.sizeGb))
            }.joinToString(" · ")
            b.tvTorrentSeeders.text = "▲ ${o.stream.seeders}  ·  ${o.addon}"
            b.root.setOnClickListener { onClick(o) }
        }
    }

    companion object {
        /** The title being browsed; set just before the sheet is shown. */
        private var pending: AppAdapter.Item? = null

        fun show(activity: FragmentActivity, item: AppAdapter.Item) {
            pending = item
            TorrentPickerSheet().show(activity.supportFragmentManager, "torrent-picker")
        }
    }
}
