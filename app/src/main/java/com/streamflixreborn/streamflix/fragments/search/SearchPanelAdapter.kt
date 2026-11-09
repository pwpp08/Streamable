package com.streamflixreborn.streamflix.fragments.search

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemSearchPanelHeaderBinding
import com.streamflixreborn.streamflix.databinding.ItemSearchPanelRowBinding
import com.streamflixreborn.streamflix.ui.tvFocusSpring
import com.streamflixreborn.streamflix.utils.CinemaStatus
import com.streamflixreborn.streamflix.utils.ThemeManager

/**
 * Recent searches and suggestions under the search box (phone and TV). Recent searches show a
 * clock icon; suggestions show their year and a Movie / TV show badge. On TV ([isTv]) rows are
 * larger, tonal, and show a focus ring with a spring scale.
 */
class SearchPanelAdapter(
    private val onQuery: (String) -> Unit,
    private val onClearHistory: () -> Unit,
    private val isTv: Boolean = false,
) : ListAdapter<SearchPanelRow, RecyclerView.ViewHolder>(Diff) {

    private object Diff : DiffUtil.ItemCallback<SearchPanelRow>() {
        override fun areItemsTheSame(oldItem: SearchPanelRow, newItem: SearchPanelRow) = oldItem == newItem
        override fun areContentsTheSame(oldItem: SearchPanelRow, newItem: SearchPanelRow) = oldItem == newItem
    }

    override fun getItemViewType(position: Int) = if (getItem(position) is SearchPanelRow.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val header = ItemSearchPanelHeaderBinding.inflate(inflater, parent, false)
            if (isTv) header.tvSearchPanelHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            object : RecyclerView.ViewHolder(header.root) {}
        } else {
            val binding = ItemSearchPanelRowBinding.inflate(inflater, parent, false)
            if (isTv) styleForTv(binding)
            RowHolder(binding)
        }
    }

    private fun styleForTv(binding: ItemSearchPanelRowBinding) {
        val context = binding.root.context
        val density = context.resources.displayMetrics.density
        binding.root.apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_sf_tv_list_item)
            minimumHeight = (60 * density).toInt()
            setPaddingRelative((20 * density).toInt(), paddingTop, (20 * density).toInt(), paddingBottom)
            (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = (4 * density).toInt()
            tvFocusSpring(1.02f)
        }
        binding.ivSearchPanelIcon.layoutParams = binding.ivSearchPanelIcon.layoutParams.apply {
            width = (24 * density).toInt()
            height = (24 * density).toInt()
        }
        binding.tvSearchPanelRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        binding.tvSearchPanelMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is SearchPanelRow.Header ->
                ItemSearchPanelHeaderBinding.bind(holder.itemView).tvSearchPanelHeader.text = row.title
            else -> (holder as RowHolder).bind(row)
        }
    }

    private inner class RowHolder(private val binding: ItemSearchPanelRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SearchPanelRow) {
            val context = binding.root.context
            val onSurface = ThemeManager.color(context, com.google.android.material.R.attr.colorOnSurface)
            binding.tvSearchPanelRow.setTextColor(onSurface)
            binding.tvSearchPanelMeta.visibility = View.GONE
            binding.tvSearchPanelBadge.visibility = View.GONE
            binding.ivSearchPanelIcon.visibility = View.VISIBLE
            when (row) {
                is SearchPanelRow.History -> {
                    binding.ivSearchPanelIcon.setImageResource(R.drawable.ic_sf_history)
                    binding.tvSearchPanelRow.text = row.query
                    binding.root.contentDescription = null
                    binding.root.setOnClickListener { onQuery(row.query) }
                }
                is SearchPanelRow.ClearHistory -> {
                    binding.ivSearchPanelIcon.visibility = View.GONE
                    binding.tvSearchPanelRow.setText(R.string.search_clear_history)
                    binding.tvSearchPanelRow.setTextColor(
                        ThemeManager.color(context, androidx.appcompat.R.attr.colorPrimary)
                    )
                    binding.root.contentDescription = null
                    binding.root.setOnClickListener { onClearHistory() }
                }
                is SearchPanelRow.Suggestion -> {
                    val suggestion = row.suggestion
                    val type = context.getString(
                        if (suggestion.type == SearchSuggestion.Type.MOVIE) R.string.search_suggestion_movie
                        else R.string.search_suggestion_tv
                    )
                    binding.ivSearchPanelIcon.setImageResource(R.drawable.ic_search)
                    binding.tvSearchPanelRow.text = suggestion.title
                    binding.tvSearchPanelMeta.apply {
                        text = suggestion.year
                        visibility = if (suggestion.year.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                    binding.tvSearchPanelBadge.apply {
                        text = type
                        visibility = View.VISIBLE
                        tag = null
                        val id = suggestion.tmdbId
                        if (suggestion.type == SearchSuggestion.Type.MOVIE && id != null) {
                            CinemaStatus.whenOnlyInCinemas(id, suggestion.releasedMillis, this) {
                                text = context.getString(R.string.browse_in_cinemas)
                            }
                        }
                    }
                    val meta = suggestion.year?.let { context.getString(R.string.search_suggestion_meta, it, type) } ?: type
                    binding.root.contentDescription = "${suggestion.title}, $meta"
                    binding.root.setOnClickListener { onQuery(suggestion.title) }
                }
                is SearchPanelRow.Header -> Unit
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1
    }
}
