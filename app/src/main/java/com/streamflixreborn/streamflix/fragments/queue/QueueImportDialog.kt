package com.streamflixreborn.streamflix.fragments.queue

import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
                binding.llQueueImportProgress.isVisible = true
                binding.tvQueueImportResult.isVisible = false
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val result = viewModel.import(context.applicationContext, text) { done, total ->
                        binding.tvQueueImportProgress.text =
                            context.getString(R.string.queue_import_progress, (done + 1).coerceAtMost(total), total)
                    }
                    val total = result.added.size + result.notFound.size
                    binding.llQueueImportProgress.isVisible = false
                    binding.tvQueueImportResult.isVisible = true
                    binding.tvQueueImportResult.text = buildString {
                        append(context.getString(R.string.queue_import_done, result.added.size, total))
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
