package com.streamflixreborn.streamflix.fragments.settings.health

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.streamflixreborn.streamflix.utils.ThemeManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemProviderHealthHeaderBinding
import com.streamflixreborn.streamflix.databinding.ItemProviderHealthServerBinding
import com.streamflixreborn.streamflix.utils.health.HlsPlaylistParser
import com.streamflixreborn.streamflix.utils.health.ServerHealth
import java.util.Locale

/** Results grouped by provider: a header row per provider followed by its servers. */
class ProviderHealthAdapter(private val focusableRows: Boolean) :
    ListAdapter<ProviderHealthAdapter.Row, RecyclerView.ViewHolder>(Diff) {

    sealed interface Row {
        val key: String

        data class Header(val provider: String, val working: Int, val total: Int, val checking: Boolean) : Row {
            override val key get() = "h:$provider"
        }

        data class Server(val result: ServerHealth) : Row {
            override val key get() = "s:${result.provider}:${result.probe}:${result.serverName}"
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SERVER = 1

        /** Builds rows in the order providers first appear in [results]. */
        fun rows(results: List<ServerHealth>, checkingProvider: String?): List<Row> {
            val grouped = LinkedHashMap<String, MutableList<ServerHealth>>()
            checkingProvider?.let { grouped.getOrPut(it) { mutableListOf() } }
            results.forEach { grouped.getOrPut(it.provider) { mutableListOf() } += it }
            return grouped.flatMap { (provider, items) ->
                val servers = items.filterNot { it.isProviderLevel }
                listOf<Row>(
                    Row.Header(
                        provider = provider,
                        working = servers.count { it.ok },
                        total = servers.size,
                        checking = provider == checkingProvider,
                    ),
                ) + items
                    .sortedWith(compareBy<ServerHealth>({ it.probe }, { !it.ok }, { -(it.maxHeight ?: 0) }, { it.latencyMs ?: Long.MAX_VALUE }))
                    .map { Row.Server(it) }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Row.Header -> TYPE_HEADER
        is Row.Server -> TYPE_SERVER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemProviderHealthHeaderBinding.inflate(inflater, parent, false))
        } else {
            ServerHolder(ItemProviderHealthServerBinding.inflate(inflater, parent, false)).also {
                it.itemView.isFocusable = focusableRows
                it.itemView.isFocusableInTouchMode = false
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Server -> (holder as ServerHolder).bind(row.result)
        }
    }

    private class HeaderHolder(private val binding: ItemProviderHealthHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Header) {
            val context = binding.root.context
            binding.tvHealthProvider.text = row.provider
            binding.tvHealthProviderSummary.text = when {
                row.checking -> context.getString(R.string.settings_health_checking_short)
                row.total == 0 -> ""
                else -> context.getString(R.string.settings_health_provider_summary, row.working, row.total)
            }
        }
    }

    private class ServerHolder(private val binding: ItemProviderHealthServerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(result: ServerHealth) {
            val context = binding.root.context
            val dotColor = when {
                result.ok -> MaterialColors.harmonizeWithPrimary(context, ContextCompat.getColor(context, R.color.health_status_ok))
                result.isUnknown -> ThemeManager.color(context, com.google.android.material.R.attr.colorOutline)
                else -> ThemeManager.color(context, androidx.appcompat.R.attr.colorError)
            }
            binding.vHealthDot.backgroundTintList = ColorStateList.valueOf(dotColor)

            val probe = context.getString(
                if (result.probe == ServerHealth.PROBE_EPISODE) R.string.settings_health_probe_episode
                else R.string.settings_health_probe_movie,
            )
            binding.tvHealthServer.text = if (result.isProviderLevel) probe else result.serverName
            val status = when {
                result.ok -> context.getString(R.string.settings_health_status_ok)
                result.isUnknown -> context.getString(R.string.settings_health_status_no_match)
                else -> result.error ?: context.getString(R.string.settings_health_status_failed)
            }
            binding.tvHealthDetail.text = if (result.isProviderLevel) status else "$probe · $status"
            binding.tvHealthLatency.text = result.latencyMs
                ?.takeIf { !result.isProviderLevel }
                ?.let { String.format(Locale.getDefault(), "%.1f s", it / 1000.0) }
                .orEmpty()
            binding.tvHealthQuality.text = if (result.ok) HlsPlaylistParser.label(result.maxHeight) else ""

            val description = listOf(binding.tvHealthServer.text, binding.tvHealthDetail.text, binding.tvHealthLatency.text, binding.tvHealthQuality.text)
                .filter { it.isNotBlank() }.joinToString(", ")
            binding.root.contentDescription = description
        }
    }
}
