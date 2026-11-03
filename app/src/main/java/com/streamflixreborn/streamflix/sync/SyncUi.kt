package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamflixreborn.streamflix.R

/**
 * Helpers for sync screens and dialogs. The activity themes are Material 3 Expressive now, so
 * [R.style.Theme_StreamFlix_Sync] is an empty overlay and these contexts keep the app palette.
 */
object SyncUi {
    fun themed(context: Context): Context = ContextThemeWrapper(context, R.style.Theme_StreamFlix_Sync)

    fun inflater(inflater: LayoutInflater): LayoutInflater = inflater.cloneInContext(themed(inflater.context))

    fun dialog(context: Context): MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(themed(context))
}
