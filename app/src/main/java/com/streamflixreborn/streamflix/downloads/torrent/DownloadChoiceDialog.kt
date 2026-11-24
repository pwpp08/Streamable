package com.streamflixreborn.streamflix.downloads.torrent

import android.app.Activity
import android.content.Context
import android.text.format.Formatter
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.downloads.DownloadActions
import com.streamflixreborn.streamflix.downloads.DownloadStarter
import com.streamflixreborn.streamflix.downloads.StoredVideoType
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The popup shown when a download is started: which quality, and whether a subtitle should be
 * bundled into the file. Falls back to the old "stream download" when nothing can be torrented.
 */
object DownloadChoiceDialog {

    private val LANGUAGES = listOf("en", "hu", "de", "fr", "es", "it", "pt", "ro", "ru", "pl", "cs", "sk", "nl", "tr", "sv", "ja", "ko", "zh")

    private fun languageName(iso1: String): String =
        Locale(iso1).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.uppercase() }

    /** [streamFallback] runs when the viewer picks (or is left with) the streaming-server download. */
    fun show(context: Context, target: DownloadStarter.Target, streamFallback: () -> Unit) {
        val activity = DownloadActions.activityOf(context)
        if (activity == null || activity.isFinishing) {
            streamFallback()
            return
        }
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt() }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val loading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ProgressBar(activity).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(TextView(activity).apply {
                setText(R.string.download_choice_loading)
                setPadding(dp(16), 0, 0, 0)
            })
        }
        root.addView(loading)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.download_choice_title, target.title))
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.download_choice_stream) { _, _ -> streamFallback() }
            .setPositiveButton(R.string.download_choice_start, null)
            .create()
        dialog.show()
        val start = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false }

        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: MainScope()
        val job: Job = scope.launch {
            val choices = runCatching { DownloadCandidates.load(target.videoType) }.getOrDefault(emptyList())
            if (!dialog.isShowing) return@launch
            root.removeAllViews()
            if (choices.isEmpty()) {
                root.addView(TextView(activity).apply { setText(R.string.download_choice_none) })
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.GONE
                return@launch
            }
            render(activity, root, choices, target, dialog, dp)
        }
        dialog.setOnDismissListener { job.cancel() }
    }

    private fun render(
        activity: Activity,
        root: LinearLayout,
        choices: List<TorrentChoice>,
        target: DownloadStarter.Target,
        dialog: AlertDialog,
        dp: (Int) -> Int,
    ) {
        root.addView(TextView(activity).apply {
            setText(R.string.download_choice_quality)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        })
        val group = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val preferred = choices.indexOfFirst { it.height == 1080 }.takeIf { it >= 0 }
            ?: choices.indexOfFirst { it.height <= 1080 }.takeIf { it >= 0 } ?: 0
        choices.forEachIndexed { index, c ->
            val text = if (c.sizeBytes > 0) {
                activity.getString(R.string.download_choice_option, c.height, Formatter.formatShortFileSize(activity, c.sizeBytes), c.seeders, c.site)
            } else {
                activity.getString(R.string.download_choice_option_unknown_size, c.height, c.seeders, c.site)
            }
            group.addView(
                RadioButton(activity).apply {
                    id = View.generateViewId()
                    this.text = text
                    tag = index
                    isChecked = index == preferred
                },
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(group)

        val spoken = UserPreferences.spokenLanguages
        val languages = (spoken + LANGUAGES).distinct()
        val subtitleBox = CheckBox(activity).apply {
            setText(R.string.download_choice_subtitle)
            isChecked = false
        }
        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, languages.map(::languageName))
            setSelection(languages.indexOf(spoken.firstOrNull { it != "en" } ?: "en").coerceAtLeast(0))
            visibility = View.GONE
            contentDescription = activity.getString(R.string.download_choice_subtitle_language)
        }
        subtitleBox.setOnCheckedChangeListener { _, checked -> spinner.visibility = if (checked) View.VISIBLE else View.GONE }
        root.addView(subtitleBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(spinner)
        root.addView(TextView(activity).apply {
            setText(R.string.download_choice_files_note)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding(0, dp(12), 0, 0)
        })

        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        button.isEnabled = true
        button.setOnClickListener {
            val checked = group.findViewById<RadioButton>(group.checkedRadioButtonId)
            val choice = choices[(checked?.tag as? Int) ?: preferred]
            val wish = if (subtitleBox.isChecked) {
                val iso = languages[spinner.selectedItemPosition.coerceIn(0, languages.lastIndex)]
                SubtitleWish(iso, languageName(iso))
            } else null
            DownloadActions.requestNotificationPermission(activity)
            TorrentDownloader.enqueue(
                TorrentDownloadRecord(
                    id = target.id,
                    provider = target.providerName,
                    videoType = StoredVideoType.of(target.videoType),
                    title = target.title,
                    subtitle = target.subtitle,
                    poster = target.poster,
                    choice = choice,
                    subtitleWish = wish,
                ),
            )
            dialog.dismiss()
        }
    }
}
