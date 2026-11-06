package com.streamflixreborn.streamflix.fragments.queue

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.DialogQueueReactionBinding
import com.streamflixreborn.streamflix.fragments.queue.QueueStore.Reaction
import com.streamflixreborn.streamflix.models.Movie
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Announces movies the viewer just marked as watched, so the foreground screen can ask how it was. */
object WatchEvents {
    private val _movieWatched = MutableSharedFlow<Movie>(extraBufferCapacity = 4)
    val movieWatched: SharedFlow<Movie> = _movieWatched.asSharedFlow()

    fun movieWatched(movie: Movie) {
        _movieWatched.tryEmit(movie)
    }
}

/** "How was it?" with the five ratings; what is picked shows up as a filter in the Watched list. */
object ReactionPrompt {

    fun show(context: Context, movie: Movie) {
        val key = QueueStore.key(false, movie.id)
        if (QueueStore.reaction(key) != null) return

        val binding = DialogQueueReactionBinding.inflate(LayoutInflater.from(context))
        val buttons = listOf(
            binding.btnReaction1, binding.btnReaction2, binding.btnReaction3,
            binding.btnReaction4, binding.btnReaction5,
        )
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.queue_reaction_prompt, movie.title))
            .setView(binding.root)
            .setNegativeButton(R.string.queue_reaction_skip, null)
            .create()
        Reaction.entries.zip(buttons).forEach { (reaction, button) ->
            button.text = "${reaction.emoji}\n${context.getString(reaction.labelRes)}"
            button.contentDescription = context.getString(reaction.labelRes)
            button.setOnClickListener {
                QueueStore.setReaction(key, reaction)
                dialog.dismiss()
            }
        }
        dialog.show()
        buttons.first().requestFocus()
    }
}
