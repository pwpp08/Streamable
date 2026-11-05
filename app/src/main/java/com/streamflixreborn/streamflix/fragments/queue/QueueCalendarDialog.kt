package com.streamflixreborn.streamflix.fragments.queue

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.DialogQueueCalendarBinding
import com.streamflixreborn.streamflix.databinding.ItemQueueCalendarBinding
import kotlinx.coroutines.launch
import java.util.Calendar

/** Upcoming episodes and premieres for the titles in the Queue, grouped by day. */
object QueueCalendarDialog {

    fun show(fragment: Fragment, viewModel: QueueViewModel) {
        val context = fragment.requireContext()
        val inflater = LayoutInflater.from(context)
        val binding = DialogQueueCalendarBinding.inflate(inflater)
        val dialog = BottomSheetDialog(context).apply { setContentView(binding.root) }
        dialog.show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val upcoming = runCatching { viewModel.upcoming() }.getOrDefault(emptyList())
            binding.liQueueCalendar.isVisible = false
            binding.tvQueueCalendarEmpty.isVisible = upcoming.isEmpty()
            binding.svQueueCalendar.isVisible = upcoming.isNotEmpty()

            var lastDay: String? = null
            upcoming.forEach { item ->
                val day = dayLabel(context, item.date)
                if (day != lastDay) {
                    lastDay = day
                    binding.llQueueCalendarItems.addView(
                        TextView(context).apply {
                            text = day
                            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                            setPadding((24 * resources.displayMetrics.density).toInt())
                            setPadding(paddingLeft, (16 * resources.displayMetrics.density).toInt(), paddingRight, (4 * resources.displayMetrics.density).toInt())
                        },
                    )
                }
                val row = ItemQueueCalendarBinding.inflate(inflater, binding.llQueueCalendarItems, false)
                row.tvCalendarTitle.text = item.entry.title
                row.tvCalendarDetail.text = if (item.episodeNumber != null) {
                    listOfNotNull(
                        context.getString(R.string.queue_meta_episode, item.seasonNumber ?: 0, item.episodeNumber),
                        item.episodeTitle?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                } else {
                    context.getString(R.string.queue_calendar_premiere)
                }
                Glide.with(row.ivCalendarPoster).load(item.entry.poster).centerCrop().into(row.ivCalendarPoster)
                row.root.setOnClickListener {
                    dialog.dismiss()
                    QueueNavigator.openDetails(fragment.findNavController(), item.entry)
                }
                binding.llQueueCalendarItems.addView(row.root)
            }
        }
    }

    private fun dayLabel(context: android.content.Context, date: Calendar): String {
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            same(date, today) -> context.getString(R.string.queue_calendar_today)
            same(date, tomorrow) -> context.getString(R.string.queue_calendar_tomorrow)
            else -> DateUtils.formatDateTime(
                context,
                date.timeInMillis,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
            )
        }
    }
}
