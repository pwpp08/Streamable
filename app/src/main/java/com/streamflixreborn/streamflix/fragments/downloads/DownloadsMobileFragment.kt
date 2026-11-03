package com.streamflixreborn.streamflix.fragments.downloads

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.NavMainGraphMobileDirections
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.FragmentDownloadsMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemDownloadMobileBinding
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.downloads.DownloadActions
import com.streamflixreborn.streamflix.downloads.DownloadPreferences
import com.streamflixreborn.streamflix.downloads.DownloadStarter
import com.streamflixreborn.streamflix.downloads.DownloadTracker
import com.streamflixreborn.streamflix.downloads.DownloadUtil
import com.streamflixreborn.streamflix.downloads.StoredVideoType
import com.streamflixreborn.streamflix.sync.SyncProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Phone Downloads screen: downloaded and running downloads, storage use and download settings. */
class DownloadsMobileFragment : Fragment() {

    private var _binding: FragmentDownloadsMobileBinding? = null
    private val binding get() = _binding!!

    private val adapter = DownloadsAdapter(
        onClick = { view, item -> onItemClicked(view, item) },
        onPauseResume = { item -> onPauseResume(item) },
        onDelete = { item -> onDelete(item) },
    )
    private var resumeChecked = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadsMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        DownloadTracker.ensureStarted(context)

        binding.rvDownloads.adapter = adapter

        binding.cbDownloadsWifiOnly.isChecked = DownloadPreferences.wifiOnly(context)
        binding.cbDownloadsWifiOnly.setOnCheckedChangeListener { _, checked ->
            DownloadPreferences.setWifiOnly(context, checked)
            DownloadStarter.applyRequirements(context)
        }
        binding.cbDownloadsSaveSpace.isChecked = DownloadPreferences.saveSpace(context)
        binding.cbDownloadsSaveSpace.setOnCheckedChangeListener { _, checked ->
            DownloadPreferences.setSaveSpace(context, checked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadTracker.items.collectLatest { items ->
                    val list = items.values.filter { it.status != DownloadTracker.Status.REMOVING }
                    adapter.submitList(list)
                    binding.tvDownloadsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    if (!resumeChecked && list.isNotEmpty()) {
                        resumeChecked = true
                        DownloadStarter.resumePending(context)
                    }
                    updateStorage()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private suspend fun updateStorage() {
        val context = context ?: return
        val (used, free) = withContext(Dispatchers.IO) {
            DownloadUtil.usedBytes(context) to DownloadUtil.freeBytes(context)
        }
        _binding?.tvDownloadsStorage?.text = getString(
            R.string.downloads_storage,
            Formatter.formatShortFileSize(context, used),
            Formatter.formatShortFileSize(context, free),
        )
    }

    private fun onItemClicked(anchor: View, item: DownloadTracker.Item) {
        when (item.status) {
            DownloadTracker.Status.COMPLETED -> play(item)
            DownloadTracker.Status.RESOLVING, DownloadTracker.Status.REMOVING -> Unit
            else -> DownloadActions.showMenu(anchor, item)
        }
    }

    private fun onPauseResume(item: DownloadTracker.Item) {
        val context = requireContext()
        when (item.status) {
            DownloadTracker.Status.PAUSED -> DownloadStarter.resume(context, item.id)
            DownloadTracker.Status.FAILED -> DownloadStarter.retry(context, item)
            DownloadTracker.Status.QUEUED,
            DownloadTracker.Status.WAITING,
            DownloadTracker.Status.DOWNLOADING -> DownloadStarter.pause(context, item.id)
            else -> Unit
        }
    }

    private fun onDelete(item: DownloadTracker.Item) {
        val context = requireContext()
        if (item.status == DownloadTracker.Status.COMPLETED) {
            DownloadActions.confirmDelete(context, item)
        } else {
            DownloadStarter.remove(context, item.id)
        }
    }

    /** Plays from the download cache: the player finds the completed download for this title. */
    private fun play(item: DownloadTracker.Item) {
        val metadata = item.metadata ?: return
        // The player looks downloads up for the current provider, and saves progress in its database.
        if (!SyncProviders.switchTo(metadata.provider)) {
            Toast.makeText(requireContext(), R.string.download_error_no_provider, Toast.LENGTH_SHORT).show()
            return
        }
        val type = metadata.videoType.toVideoType()
        findNavController().navigate(
            NavMainGraphMobileDirections.actionGlobalPlayer(
                id = metadata.videoType.id,
                title = metadata.title,
                subtitle = metadata.subtitle,
                videoType = type,
            )
        )
    }

    private class DownloadsAdapter(
        private val onClick: (View, DownloadTracker.Item) -> Unit,
        private val onPauseResume: (DownloadTracker.Item) -> Unit,
        private val onDelete: (DownloadTracker.Item) -> Unit,
    ) : ListAdapter<DownloadTracker.Item, DownloadsAdapter.Holder>(Diff) {

        object Diff : DiffUtil.ItemCallback<DownloadTracker.Item>() {
            override fun areItemsTheSame(oldItem: DownloadTracker.Item, newItem: DownloadTracker.Item) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: DownloadTracker.Item, newItem: DownloadTracker.Item) = oldItem == newItem
        }

        inner class Holder(val binding: ItemDownloadMobileBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDownloadMobileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val binding = holder.binding
            val context = binding.root.context
            val metadata = item.metadata

            binding.tvDownloadTitle.text = metadata?.title ?: item.id
            binding.tvDownloadSubtitle.text = metadata?.let { secondLine(context, it.videoType, it.subtitle) }.orEmpty()
            binding.tvDownloadSubtitle.visibility = if (binding.tvDownloadSubtitle.text.isNullOrEmpty()) View.GONE else View.VISIBLE

            Glide.with(binding.ivDownloadPoster)
                .load(metadata?.poster)
                .error(R.drawable.glide_fallback_cover)
                .centerCrop()
                .into(binding.ivDownloadPoster)

            val size = when {
                item.status == DownloadTracker.Status.COMPLETED || item.contentLength <= 0 ->
                    item.bytesDownloaded.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
                else -> "${Formatter.formatShortFileSize(context, item.bytesDownloaded)} / " +
                    Formatter.formatShortFileSize(context, item.contentLength)
            }
            val failure = item.message?.takeIf { item.status == DownloadTracker.Status.FAILED }
            binding.tvDownloadStatus.text = listOfNotNull(size, DownloadActions.statusText(context, item), failure)
                .joinToString(" · ")

            val showProgress = item.status in setOf(
                DownloadTracker.Status.DOWNLOADING,
                DownloadTracker.Status.PAUSED,
                DownloadTracker.Status.QUEUED,
                DownloadTracker.Status.WAITING,
                DownloadTracker.Status.RESOLVING,
            )
            bindProgress(binding, item, showProgress)
            bindStateColor(binding, item)

            val pauseIcon = when (item.status) {
                DownloadTracker.Status.PAUSED -> R.drawable.ic_download_resume to R.string.download_menu_resume
                DownloadTracker.Status.FAILED -> R.drawable.ic_refresh to R.string.download_menu_retry
                DownloadTracker.Status.QUEUED,
                DownloadTracker.Status.WAITING,
                DownloadTracker.Status.DOWNLOADING -> R.drawable.ic_download_pause to R.string.download_menu_pause
                else -> null
            }
            binding.btnDownloadPause.visibility = if (pauseIcon != null) View.VISIBLE else View.GONE
            pauseIcon?.let { (icon, label) ->
                binding.btnDownloadPause.setImageResource(icon)
                binding.btnDownloadPause.contentDescription = context.getString(label)
            }
            binding.btnDownloadDelete.visibility =
                if (item.status == DownloadTracker.Status.RESOLVING) View.GONE else View.VISIBLE

            binding.root.setOnClickListener { onClick(it, item) }
            binding.btnDownloadPause.setOnClickListener { onPauseResume(item) }
            binding.btnDownloadDelete.setOnClickListener { onDelete(item) }
        }

        /**
         * Wavy while bytes are flowing, flat and muted while paused or waiting; indeterminate
         * while the stream is being resolved.
         */
        private fun bindProgress(binding: ItemDownloadMobileBinding, item: DownloadTracker.Item, show: Boolean) {
            val bar = binding.pbDownloadProgress
            if (!show) {
                bar.visibility = View.GONE
                return
            }
            val context = bar.context
            val active = item.status == DownloadTracker.Status.DOWNLOADING ||
                item.status == DownloadTracker.Status.RESOLVING
            val density = context.resources.displayMetrics.density
            bar.waveAmplitude = if (active) (3 * density).toInt() else 0
            bar.setIndicatorColor(
                ThemeManager.color(
                    context,
                    if (active) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutline,
                )
            )
            val wasVisible = bar.visibility == View.VISIBLE
            bar.visibility = View.VISIBLE
            bar.isIndeterminate = item.status == DownloadTracker.Status.RESOLVING
            bar.setProgressCompat(item.percent.coerceAtLeast(0), wasVisible)
        }

        private fun bindStateColor(binding: ItemDownloadMobileBinding, item: DownloadTracker.Item) {
            val context = binding.root.context
            val attr = when (item.status) {
                DownloadTracker.Status.FAILED -> androidx.appcompat.R.attr.colorError
                DownloadTracker.Status.COMPLETED -> androidx.appcompat.R.attr.colorPrimary
                else -> com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            binding.tvDownloadStatus.setTextColor(ThemeManager.color(context, attr))
        }

        private fun secondLine(context: android.content.Context, type: StoredVideoType, subtitle: String): String =
            if (type.kind == StoredVideoType.KIND_EPISODE) {
                context.getString(R.string.download_item_subtitle_episode, type.seasonNumber, type.number, type.title.orEmpty()).trim()
            } else {
                subtitle
            }
    }
}
