package com.streamflixreborn.streamflix.fragments.queue

import android.content.ClipboardManager
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.DialogQueueImportBinding
import kotlinx.coroutines.launch

/** Paste a list (notes, spreadsheet column, numbered list) and add every title found. */
object QueueImportDialog {

    fun show(fragment: Fragment, viewModel: QueueViewModel) {
        val context = fragment.requireContext()
        val binding = DialogQueueImportBinding.inflate(LayoutInflater.from(context))

        // Start with the clipboard when it already looks like a list.
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (clip != null && clip.lines().count { it.isNotBlank() } >= 2) binding.etQueueImport.setText(clip)

        // "Add them as": the Watchlist, or Watched with a rating.
        val targets = listOf<Pair<QueueViewModel.ImportTarget, String>>(
            QueueViewModel.ImportTarget.Watchlist to context.getString(R.string.queue_import_target_watchlist),
        ) + QueueStore.Reaction.entries.map {
            QueueViewModel.ImportTarget.Rated(it) to "${it.emoji} ${context.getString(it.labelRes)}"
        }
        targets.forEachIndexed { index, (target, label) ->
            binding.cgQueueImportTarget.addView(
                Chip(ContextThemeWrapper(context, R.style.Widget_StreamFlix_Queue_FilterChip), null, 0).apply {
                    id = View.generateViewId()
                    text = label
                    tag = target
                    isCheckable = true
                    isChecked = index == 0
                },
            )
        }
        fun chosenTarget(): QueueViewModel.ImportTarget =
            binding.cgQueueImportTarget.findViewById<Chip>(binding.cgQueueImportTarget.checkedChipId)?.tag as? QueueViewModel.ImportTarget
                ?: QueueViewModel.ImportTarget.Watchlist

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.queue_import_title)
            .setView(binding.root)
            .setPositiveButton(R.string.queue_import_start, null)
            .setNegativeButton(R.string.option_cancel, null)
            .create()
        dialog.setOnShowListener {
            val start = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            var finished = false
            start.setOnClickListener {
                if (finished) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val text = binding.etQueueImport.text?.toString().orEmpty()
                if (text.isBlank()) return@setOnClickListener
                start.isEnabled = false
                binding.tilQueueImport.isEnabled = false
                binding.cgQueueImportTarget.isEnabled = false
                val target = chosenTarget()
                binding.llQueueImportProgress.isVisible = true
                binding.tvQueueImportResult.isVisible = false
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val result = viewModel.import(context.applicationContext, text, target) { done, total ->
                        binding.tvQueueImportProgress.text =
                            context.getString(R.string.queue_import_progress, (done + 1).coerceAtMost(total), total)
                    }
                    val total = result.added.size + result.notFound.size
                    binding.llQueueImportProgress.isVisible = false
                    binding.tvQueueImportResult.isVisible = true
                    binding.tvQueueImportResult.text = buildString {
                        append(
                            if (target is QueueViewModel.ImportTarget.Rated) {
                                context.getString(R.string.queue_import_done_rated, result.added.size, total, context.getString(target.reaction.labelRes))
                            } else {
                                context.getString(R.string.queue_import_done, result.added.size, total)
                            },
                        )
                        if (result.notFound.isNotEmpty()) {
                            append("\n\n")
                            append(context.getString(R.string.queue_import_not_found, result.notFound.joinToString(", ")))
                        }
                    }
                    finished = true
                    start.isEnabled = true
                    start.setText(R.string.queue_done)
                    cancel.isVisible = false
                }
            }
        }
        dialog.show()
    }
}
