package com.streamflixreborn.streamflix.fragments.queue

import android.app.Dialog
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.DialogQueueSpinBinding
import kotlin.random.Random

/** "Can't decide?": spins a wheel of up to 12 Watchlist titles and offers to play the pick. */
object QueueSpinDialog {
    private const val MAX_SLICES = 12

    fun show(fragment: Fragment, watchlist: List<QueueEntry>, isTv: Boolean) {
        val context = fragment.requireContext()
        if (watchlist.size < 2) {
            Toast.makeText(context, R.string.queue_spin_need_more, Toast.LENGTH_LONG).show()
            return
        }
        val pool = watchlist.shuffled().take(MAX_SLICES)
        val binding = DialogQueueSpinBinding.inflate(LayoutInflater.from(context))
        lateinit var dialog: Dialog
        var pick: QueueEntry? = null

        binding.wheelQueue.setLabels(pool.map { it.title })

        fun spin() {
            if (binding.wheelQueue.isSpinning) return
            val index = Random.nextInt(pool.size)
            binding.llQueueSpinResult.isVisible = false
            binding.bgQueueSpinActions.isVisible = false
            binding.btnQueueSpin.isEnabled = false
            binding.wheelQueue.spin(index) {
                val chosen = pool[index]
                pick = chosen
                binding.tvQueueSpinPick.text = chosen.title
                Glide.with(binding.ivQueueSpinPoster).load(chosen.poster).centerCrop().into(binding.ivQueueSpinPoster)
                binding.llQueueSpinResult.isVisible = true
                binding.bgQueueSpinActions.isVisible = true
                binding.btnQueueSpinPlay.isVisible = QueueNavigator.canPlay(chosen)
                binding.btnQueueSpin.isEnabled = true
                binding.btnQueueSpin.setText(R.string.queue_spin_again)
                if (isTv) {
                    (if (binding.btnQueueSpinPlay.isVisible) binding.btnQueueSpinPlay else binding.btnQueueSpinDetails).requestFocus()
                }
            }
        }

        binding.btnQueueSpin.setOnClickListener { spin() }
        binding.wheelQueue.setOnClickListener { spin() }
        binding.btnQueueSpinPlay.setOnClickListener {
            val chosen = pick ?: return@setOnClickListener
            dialog.dismiss()
            QueueNavigator.play(context, fragment.findNavController(), chosen)
        }
        binding.btnQueueSpinDetails.setOnClickListener {
            val chosen = pick ?: return@setOnClickListener
            dialog.dismiss()
            QueueNavigator.openDetails(fragment.findNavController(), chosen)
        }

        dialog = if (isTv) {
            MaterialAlertDialogBuilder(context).setView(binding.root).create()
        } else {
            BottomSheetDialog(context).apply {
                setContentView(binding.root)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        dialog.show()
        binding.btnQueueSpin.requestFocus()
    }
}
