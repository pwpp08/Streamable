package com.streamflixreborn.streamflix.fragments.settings

import androidx.preference.PreferenceFragmentCompat
import com.streamflixreborn.streamflix.R

/**
 * Turns the preference list of [SettingsMobileFragment] / [SettingsTvFragment] into the expressive
 * grouped list: no dividers, list padding, and [SettingsGroupDecoration] for the rounded rows.
 * The row layouts themselves come from the preference theme (values/styles_ui_misc.xml).
 */
internal object SettingsListStyler {

    fun attach(fragment: PreferenceFragmentCompat, isTv: Boolean) {
        fragment.setDivider(null)
        val list = fragment.listView ?: return
        if (list.getTag(R.id.settings_list_styler_tag) == true) return
        list.setTag(R.id.settings_list_styler_tag, true)

        val density = list.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density + 0.5f).toInt()
        list.clipToPadding = false
        list.clipChildren = false
        list.setPadding(0, px(if (isTv) 4 else 0), 0, px(if (isTv) 48 else 32))
        list.addItemDecoration(SettingsGroupDecoration(list.context, isTv))
    }
}
