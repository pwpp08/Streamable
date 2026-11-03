package com.streamflixreborn.streamflix.fragments.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemSearchPanelHeaderBinding
import com.streamflixreborn.streamflix.databinding.ItemSearchPanelRowBinding

/** Recent searches and suggestions under the search box (phone and TV). */
class SearchPanelAdapter(
    private val onQuery: (String) -> Unit,
    private val onClearHistory: () -> Unit,
) : ListAdapter<SearchPanelRow, RecyclerView.ViewHolder>(Diff) {

    private object Diff : DiffUtil.ItemCallback<SearchPanelRow>() {
        override fun areItemsTheSame(oldItem: SearchPanelRow, newItem: SearchPanelRow) = oldItem == newItem
        override fun areContentsTheSame(oldItem: SearchPanelRow, newItem: SearchPanelRow) = oldItem == newItem
    }

    override fun getItemViewType(position: Int) = if (getItem(position) is SearchPanelRow.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            object : RecyclerView.ViewHolder(ItemSearchPanelHeaderBinding.inflate(inflater, parent, false).root) {}
        } else {
            RowHolder(ItemSearchPanelRowBinding.inflate(inflater, parent, false))
        }
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
            when (row) {
                is SearchPanelRow.History -> {
                    binding.tvSearchPanelRow.text = row.query
                    binding.root.setOnClickListener { onQuery(row.query) }
                }
                is SearchPanelRow.ClearHistory -> {
                    binding.tvSearchPanelRow.setText(R.string.search_clear_history)
                    binding.root.setOnClickListener { onClearHistory() }
                }
                is SearchPanelRow.Suggestion -> {
                    val suggestion = row.suggestion
                    val type = context.getString(
                        if (suggestion.type == SearchSuggestion.Type.MOVIE) R.string.search_suggestion_movie
                        else R.string.search_suggestion_tv
                    )
                    val meta = suggestion.year?.let { context.getString(R.string.search_suggestion_meta, it, type) } ?: type
                    binding.tvSearchPanelRow.text = "${suggestion.title}  ($meta)"
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
