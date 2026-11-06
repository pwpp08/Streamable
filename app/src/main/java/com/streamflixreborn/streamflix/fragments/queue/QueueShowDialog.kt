package com.streamflixreborn.streamflix.fragments.queue

import android.app.Dialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.DialogQueueShowBinding
import com.streamflixreborn.streamflix.databinding.ItemQueueSeasonEpisodeBinding
import com.streamflixreborn.streamflix.databinding.ItemQueueSeasonHeaderBinding
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.ReleaseStatus
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.WatchStateActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * A show from Up next as a list of its seasons (the "Season 1 · 94% · 3 of 8 episodes watched" view):
 * a season expands into its episodes, each with a watched tick, and tapping an episode plays it.
 */
object QueueShowDialog {

    private class SeasonState(val season: Season, val episodes: List<Episode>, val watched: MutableMap<String, Boolean>)

    sealed interface Row {
        data class SeasonHeader(
            val id: String,
            val title: String,
            val stats: String,
            val flags: List<Boolean>,
            val expanded: Boolean,
        ) : Row

        data class EpisodeItem(
            val episode: Episode,
            val title: String,
            val meta: String,
            val watched: Boolean,
            val unreleased: Boolean,
        ) : Row
    }

    fun show(fragment: Fragment, entry: QueueEntry) {
        val context = fragment.requireContext()
        val binding = DialogQueueShowBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context, R.style.ThemeOverlay_StreamFlix_FullscreenDialog)
        dialog.setContentView(binding.root)
        val scope = fragment.viewLifecycleOwner.lifecycleScope

        var seasons: List<SeasonState> = emptyList()
        val expanded = mutableSetOf<String>()
        lateinit var adapter: Adapter

        fun rows(): List<Row> = buildList {
            seasons.forEach { state ->
                val total = state.episodes.size
                val done = state.episodes.count { state.watched[it.id] == true }
                val percent = if (total == 0) 0 else done * 100 / total
                val isOpen = state.season.id in expanded
                add(
                    Row.SeasonHeader(
                        id = state.season.id,
                        title = seasonTitle(context, state.season),
                        stats = if (total == 0) context.getString(R.string.queue_season_stats_empty)
                        else context.getString(R.string.queue_season_stats, percent, done, total),
                        flags = state.episodes.map { state.watched[it.id] == true },
                        expanded = isOpen,
                    ),
                )
                if (isOpen) {
                    state.episodes.forEach { episode ->
                        val released = episode.released
                        val unreleased = ReleaseStatus.isUnreleased(released)
                        add(
                            Row.EpisodeItem(
                                episode = episode,
                                title = context.getString(
                                    R.string.queue_episode_title,
                                    episode.number,
                                    episode.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.episode_number, episode.number),
                                ),
                                meta = when {
                                    released == null -> ""
                                    unreleased -> context.getString(R.string.queue_episode_airs, ReleaseStatus.shortDate(context, released))
                                    else -> context.getString(R.string.queue_episode_aired, ReleaseStatus.shortDate(context, released))
                                },
                                watched = state.watched[episode.id] == true,
                                unreleased = unreleased,
                            ),
                        )
                    }
                }
            }
        }

        fun refresh() = adapter.submitList(rows())

        adapter = Adapter(
            onSeason = { id ->
                if (!expanded.remove(id)) expanded.add(id)
                refresh()
            },
            onEpisode = { episode ->
                dialog.dismiss()
                QueueNavigator.play(context, fragment.findNavController(), entry.copy(episode = episode, seasonNumber = episode.season?.number, episodeNumber = episode.number))
            },
            onWatched = { episode, watched ->
                seasons.firstOrNull { it.season.id == episode.season?.id }?.watched?.set(episode.id, watched)
                WatchStateActions.launch(context) { WatchStateActions.setWatched(context, episode, watched) }
                refresh()
            },
        )
        binding.rvQueueShow.layoutManager = LinearLayoutManager(context)
        binding.rvQueueShow.adapter = adapter
        binding.rvQueueShow.itemAnimator = null
        binding.tvQueueShowTitle.text = entry.title
        binding.btnQueueShowClose.setOnClickListener { dialog.dismiss() }
        binding.btnQueueShowDetails.setOnClickListener {
            dialog.dismiss()
            QueueNavigator.openDetails(fragment.findNavController(), entry)
        }

        dialog.show()

        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { load(context, entry) }.getOrNull() }
            if (!dialog.isShowing) return@launch
            binding.liQueueShow.isVisible = false
            if (loaded == null || loaded.isEmpty()) {
                binding.tvQueueShowError.isVisible = true
                return@launch
            }
            seasons = loaded
            // Open the season the next episode belongs to.
            val current = entry.episode?.season?.id
            (loaded.firstOrNull { it.season.id == current } ?: loaded.firstOrNull { s -> s.episodes.any { s.watched[it.id] != true } } ?: loaded.first())
                .let { expanded.add(it.season.id) }
            refresh()
        }
    }

    private fun seasonTitle(context: android.content.Context, season: Season): String =
        if (season.number == 0) context.getString(R.string.queue_season_specials)
        else context.getString(R.string.queue_season_title, season.number)

    /** The show's seasons with their episodes, merged with what is stored as watched. */
    private suspend fun load(context: android.content.Context, entry: QueueEntry): List<SeasonState> = coroutineScope {
        val provider = UserPreferences.currentProvider ?: return@coroutineScope emptyList()
        val show = provider.getTvShow(entry.id)
        val local = AppDatabase.getInstance(context).episodeDao().getByTvShowId(entry.id).associateBy { it.id }
        val permits = Semaphore(4)
        val ordered = show.seasons.sortedWith(compareBy({ it.number == 0 }, { it.number }))
        val owner = entry.tvShow ?: TvShow(id = entry.id, title = entry.title, poster = entry.poster)
        ordered.map { season ->
            async {
                val episodes = permits.withPermit {
                    runCatching { provider.getEpisodesBySeason(season.id) }.getOrDefault(emptyList())
                }.sortedBy { it.number }
                episodes.forEach { episode ->
                    if (episode.tvShow == null) episode.tvShow = owner
                    if (episode.season == null) episode.season = season
                }
                SeasonState(season, episodes, episodes.associate { it.id to (local[it.id]?.isWatched == true) }.toMutableMap())
            }
        }.awaitAll().filter { it.episodes.isNotEmpty() }
    }

    private class Adapter(
        private val onSeason: (String) -> Unit,
        private val onEpisode: (Episode) -> Unit,
        private val onWatched: (Episode, Boolean) -> Unit,
    ) : ListAdapter<Row, RecyclerView.ViewHolder>(Diff) {

        private class HeaderHolder(val binding: ItemQueueSeasonHeaderBinding) : RecyclerView.ViewHolder(binding.root)
        private class EpisodeHolder(val binding: ItemQueueSeasonEpisodeBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemViewType(position: Int) = if (getItem(position) is Row.SeasonHeader) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderHolder(ItemQueueSeasonHeaderBinding.inflate(inflater, parent, false))
            else EpisodeHolder(ItemQueueSeasonEpisodeBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is Row.SeasonHeader -> (holder as HeaderHolder).binding.run {
                    tvSeasonTitle.text = row.title
                    tvSeasonStats.text = row.stats
                    spvSeason.isVisible = row.flags.isNotEmpty()
                    spvSeason.setWatched(row.flags)
                    ivSeasonExpand.rotation = if (row.expanded) 180f else 0f
                    root.setOnClickListener { onSeason(row.id) }
                }

                is Row.EpisodeItem -> (holder as EpisodeHolder).binding.run {
                    tvEpisodeTitle.text = row.title
                    tvEpisodeMeta.text = row.meta
                    tvEpisodeMeta.isVisible = row.meta.isNotBlank()
                    Glide.with(ivEpisodeStill).load(row.episode.poster).centerCrop().into(ivEpisodeStill)
                    btnEpisodeWatched.isEnabled = !row.unreleased
                    btnEpisodeWatched.setOnClickListener(null)
                    btnEpisodeWatched.isChecked = row.watched
                    btnEpisodeWatched.setOnClickListener {
                        it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        onWatched(row.episode, !row.watched)
                    }
                    root.alpha = if (row.unreleased) 0.6f else 1f
                    root.setOnClickListener { if (!row.unreleased) onEpisode(row.episode) }
                }
            }
        }

        private object Diff : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = when {
                a is Row.SeasonHeader && b is Row.SeasonHeader -> a.id == b.id
                a is Row.EpisodeItem && b is Row.EpisodeItem -> a.episode.id == b.episode.id
                else -> false
            }

            override fun areContentsTheSame(a: Row, b: Row) = when {
                a is Row.SeasonHeader && b is Row.SeasonHeader -> a == b
                a is Row.EpisodeItem && b is Row.EpisodeItem ->
                    a.title == b.title && a.meta == b.meta && a.watched == b.watched && a.unreleased == b.unreleased
                else -> false
            }
        }
    }
}
