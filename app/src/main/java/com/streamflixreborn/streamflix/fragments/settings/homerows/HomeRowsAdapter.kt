package com.streamflixreborn.streamflix.fragments.settings.homerows

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemHomeRowBinding
import com.streamflixreborn.streamflix.fragments.home.HomeRowsConfig

/**
 * Rows of Settings > Home screen. Phone: drag handle (via [onStartDrag]); TV: Move up / Move down
 * buttons. Clicking a row toggles its visibility. Every change is reported through [onChanged].
 */
class HomeRowsAdapter(
    private val isTv: Boolean,
    private val titleOf: (String) -> String,
    private val subtitleOf: (String) -> String,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onChanged: (HomeRowsConfig) -> Unit,
) : RecyclerView.Adapter<HomeRowsAdapter.Holder>() {

    private var rows: MutableList<HomeRowsConfig.Row> = mutableListOf()

    val config: HomeRowsConfig get() = HomeRowsConfig(rows.toList())

    @SuppressLint("NotifyDataSetChanged")
    fun submit(config: HomeRowsConfig) {
        rows = config.rows.toMutableList()
        notifyDataSetChanged()
    }

    /** Moves a row without saving (drag in progress). */
    fun move(from: Int, to: Int) {
        if (from !in rows.indices || to !in rows.indices || from == to) return
        rows = config.moved(from, to).rows.toMutableList()
        notifyItemMoved(from, to)
    }

    private fun moveAndSave(from: Int, to: Int) {
        if (to !in rows.indices) return
        move(from, to)
        // Refresh both rows so the first/last row's buttons update.
        notifyItemChanged(from)
        notifyItemChanged(to)
        onChanged(config)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemHomeRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    inner class Holder(private val binding: ItemHomeRowBinding) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("ClickableViewAccessibility")
        fun bind(row: HomeRowsConfig.Row) {
            val context = binding.root.context
            binding.tvHomeRowTitle.text = titleOf(row.key)
            binding.tvHomeRowSubtitle.text = subtitleOf(row.key)
            binding.tvHomeRowTitle.alpha = if (row.visible) 1f else 0.5f
            binding.ivHomeRowVisible.setImageResource(if (row.visible) R.drawable.ic_switch_on else R.drawable.ic_switch_off)
            binding.ivHomeRowVisible.contentDescription =
                context.getString(if (row.visible) R.string.home_rows_shown else R.string.home_rows_hidden)

            binding.root.setOnClickListener {
                val index = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                rows[index] = rows[index].copy(visible = !rows[index].visible)
                notifyItemChanged(index)
                onChanged(config)
            }

            binding.ivHomeRowDrag.visibility = if (isTv) View.GONE else View.VISIBLE
            binding.ivHomeRowDrag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this)
                false
            }

            val position = bindingAdapterPosition
            listOf(binding.btnHomeRowUp to -1, binding.btnHomeRowDown to 1).forEach { (button, delta) ->
                button.visibility = if (isTv) View.VISIBLE else View.GONE
                val target = position + delta
                button.isEnabled = target in rows.indices
                button.alpha = if (button.isEnabled) 1f else 0.4f
                button.setOnClickListener {
                    val from = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                    moveAndSave(from, from + delta)
                }
            }
        }
    }
}
