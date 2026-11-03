package com.streamflixreborn.streamflix.adapters.viewholders

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.animation.AnimationUtils
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemGenreGridMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemGenreGridTvBinding
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.ui.browse.BrowseCards


class GenreViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var genre: Genre

    fun bind(genre: Genre) {
        this.genre = genre

        when (_binding) {
            is ItemGenreGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemGenreGridTvBinding -> displayGridTvItem(_binding)
        }
    }


    private fun displayGridMobileItem(binding: ItemGenreGridMobileBinding) {
        BrowseCards.attachPress(binding.root)
        binding.root.apply {
            val colors = context.resources.getIntArray(R.array.genres)
            setCardBackgroundColor(colors[bindingAdapterPosition.coerceAtLeast(0) % colors.size])

            setOnClickListener {
                val args = Bundle().apply {
                    putString("id", genre.id)
                    putString("name", genre.name)
                }
                findNavController().navigate(R.id.genre, args)
            }
        }

        binding.tvGenreName.text = genre.name
    }

    private fun displayGridTvItem(binding: ItemGenreGridTvBinding) {
        binding.root.apply {
            val colors = context.resources.getIntArray(R.array.genres)
            setCardBackgroundColor(colors[bindingAdapterPosition.coerceAtLeast(0) % colors.size])

            setOnClickListener {
                val args = Bundle().apply {
                    putString("id", genre.id)
                    putString("name", genre.name)
                }
                findNavController().navigate(R.id.genre, args)
            }
        }
        // The card's stroke is the focus ring.
        BrowseCards.attachTvFocus(binding.root, ring = true)

        binding.tvGenreName.text = genre.name
    }
}
