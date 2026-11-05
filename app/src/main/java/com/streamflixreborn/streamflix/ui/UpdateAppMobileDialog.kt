package com.streamflixreborn.streamflix.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.databinding.DialogUpdateAppMobileBinding
import com.streamflixreborn.streamflix.utils.InAppUpdater

class UpdateAppMobileDialog(
    context: Context,
    newReleases: List<InAppUpdater.AppRelease>,
) : Dialog(context) {

    private val binding = DialogUpdateAppMobileBinding.inflate(LayoutInflater.from(context))

    var isLoading: Boolean
        get() = binding.pbUpdateIsLoading.isVisible
        set(value) {
            binding.pbUpdateIsLoading.visibility = when {
                value -> View.VISIBLE
                else -> View.GONE
            }
        }

    init {
        setContentView(binding.root)
        window?.setBackgroundDrawable(DialogSurface.create(context))

        binding.tvUpdateCurrentVersion.text = BuildConfig.VERSION_NAME

        binding.tvUpdateNewVersion.text = newReleases.first().versionName

        binding.tvUpdateReleaseNotes.text = newReleases.map {
            it.body?.replace(
                Regex("^- ([a-z0-9]+: )?(.*?)(#\\d+ )?\$", RegexOption.MULTILINE),
                "- $2"
            )
        }.joinToString("\n")

        binding.btnUpdateCancel.setOnClickListener {
            hide()
        }


        window?.setLayout(
            context.resources.displayMetrics.widthPixels,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }


    fun setOnUpdateClickListener(listener: (view: View) -> Unit) {
        binding.btnUpdate.setOnClickListener(listener)
    }
}