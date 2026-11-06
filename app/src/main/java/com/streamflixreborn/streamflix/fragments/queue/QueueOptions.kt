package com.streamflixreborn.streamflix.fragments.queue

import android.app.Dialog
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.SheetQueueOptionsBinding
import com.streamflixreborn.streamflix.fragments.queue.QueueStore.Reaction

/** Long-press sheet: react, play, open, add/remove, mark watched. A dialog on TV. */
object QueueOptions {

    fun show(fragment: Fragment, entry: QueueEntry, viewModel: QueueViewModel, isTv: Boolean) {
        val context = fragment.requireContext()
        val binding = SheetQueueOptionsBinding.inflate(LayoutInflater.from(context))
        lateinit var dialog: Dialog

        binding.tvQueueOptionsTitle.text = entry.title
        binding.tvQueueOptionsMeta.text = listOfNotNull(
            context.getString(if (entry.isTv) R.string.queue_kind_show else R.string.queue_kind_movie),
            entry.year,
        ).joinToString(" · ")
        Glide.with(binding.ivQueueOptionsPoster).load(entry.poster).centerCrop().into(binding.ivQueueOptionsPoster)

        val buttons = mapOf(
            Reaction.LOVED to binding.btnQueueReactionLoved,
            Reaction.LIKED to binding.btnQueueReactionLiked,
            Reaction.OKAY to binding.btnQueueReactionOkay,
            Reaction.DISLIKED to binding.btnQueueReactionDisliked,
            Reaction.HATED to binding.btnQueueReactionHated,
        )
        fun label(reaction: Reaction?) = reaction?.let { context.getString(it.labelRes) }.orEmpty()
        buttons.forEach { (reaction, button) ->
            button.text = reaction.emoji
            button.isChecked = entry.reaction == reaction
        }
        binding.tvQueueReactionLabel.text = label(entry.reaction)
        binding.tvQueueReactionLabel.isVisible = entry.reaction != null
        binding.tgQueueReaction.addOnButtonCheckedListener { _, checkedId, isChecked ->
            val reaction = buttons.entries.firstOrNull { it.value.id == checkedId }?.key ?: return@addOnButtonCheckedListener
            if (isChecked) {
                viewModel.setReaction(entry, reaction)
                binding.tvQueueReactionLabel.text = label(reaction)
                binding.tvQueueReactionLabel.isVisible = true
            } else if (binding.tgQueueReaction.checkedButtonId == android.view.View.NO_ID) {
                viewModel.setReaction(entry, null)
                binding.tvQueueReactionLabel.isVisible = false
            }
        }

        val canPlay = QueueNavigator.canPlay(entry)
        binding.btnQueueOptionsPlay.isEnabled = canPlay
        val releaseLabel = QueueNavigator.releaseLabel(context, entry)
        if (releaseLabel != null) {
            binding.btnQueueOptionsPlay.text = releaseLabel
        } else {
            binding.btnQueueOptionsPlay.setText(if (entry.progress != null) R.string.queue_action_resume else R.string.queue_action_play)
        }
        binding.btnQueueOptionsPlay.setOnClickListener {
            dialog.dismiss()
            QueueNavigator.playResolving(fragment, entry)
        }
        binding.btnQueueOptionsDetails.setOnClickListener {
            dialog.dismiss()
            QueueNavigator.openDetails(fragment.findNavController(), entry)
        }
        binding.btnQueueOptionsQueue.setText(if (entry.inQueue) R.string.queue_action_remove else R.string.queue_action_add)
        binding.btnQueueOptionsQueue.setIconResource(if (entry.inQueue) R.drawable.ic_queue_close else R.drawable.ic_queue_add)
        binding.btnQueueOptionsQueue.setOnClickListener {
            dialog.dismiss()
            viewModel.setInQueue(context.applicationContext, entry, !entry.inQueue)
        }
        binding.btnQueueOptionsWatched.setText(
            if (entry.watched) R.string.queue_action_mark_unwatched else R.string.queue_action_mark_watched,
        )
        binding.btnQueueOptionsWatched.setOnClickListener {
            dialog.dismiss()
            viewModel.setWatched(context.applicationContext, entry, !entry.watched)
        }

        dialog = if (isTv) {
            MaterialAlertDialogBuilder(context).setView(binding.root).create()
        } else {
            BottomSheetDialog(context).apply { setContentView(binding.root) }
        }
        dialog.show()
        if (isTv) (if (canPlay) binding.btnQueueOptionsPlay else binding.btnQueueOptionsDetails).requestFocus()
    }
}
