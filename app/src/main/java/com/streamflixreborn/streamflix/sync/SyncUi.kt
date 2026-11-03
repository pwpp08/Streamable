package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamflixreborn.streamflix.R

/**
 * The activities use AppCompat (phone) and Leanback (TV) themes, which
 * Material Components reject. Sync screens and dialogs wrap their context in
 * [R.style.Theme_StreamFlix_Sync] through these helpers.
 */
object SyncUi {
    fun themed(context: Context): Context = ContextThemeWrapper(context, R.style.Theme_StreamFlix_Sync)

    fun inflater(inflater: LayoutInflater): LayoutInflater = inflater.cloneInContext(themed(inflater.context))

    fun dialog(context: Context): MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(themed(context))
}
