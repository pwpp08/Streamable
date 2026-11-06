package com.streamflixreborn.streamflix.fragments.player.engine

import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.streamflixreborn.streamflix.utils.LanguageMatcher
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.UserPreferences

/**
 * "Subtitles: always on" with a stream that came without any subtitle: once the stream's tracks are
 * known and none is showing, the best OpenSubtitles result in the viewer's language is loaded
 * automatically. Does nothing in the other subtitle modes, and only ever fires once per player.
 */
class AutoSubtitleFetch {

    private var results: List<OpenSubtitles.Subtitle>? = null
    private var tracksKnown = false
    private var hasShownText = false
    private var fired = false

    fun onResults(subtitles: List<OpenSubtitles.Subtitle>) {
        results = subtitles
    }

    fun onTracks(tracks: Tracks) {
        tracksKnown = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_AUDIO }
        hasShownText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
    }

    /** The subtitle to download now, or null. Returns each player's one automatic pick only once. */
    fun next(sideloadedShowing: Boolean): OpenSubtitles.Subtitle? {
        if (fired) return null
        if (UserPreferences.subtitleMode != UserPreferences.SubtitleMode.ALWAYS) return null
        val found = results ?: return null
        if (!tracksKnown || hasShownText || sideloadedShowing) return null
        val pick = pick(found, UserPreferences.spokenLanguages) ?: return null
        fired = true
        return pick
    }

    companion object {
        /**
         * The first spoken language that has a result wins; inside it, normal subtitles (not
         * hearing-impaired or machine-translated) with the most downloads come first.
         */
        fun pick(subtitles: List<OpenSubtitles.Subtitle>, spokenIso1: List<String>): OpenSubtitles.Subtitle? {
            val usable = subtitles.filter {
                it.subDownloadLink.isNotBlank() && !it.subFileName.isNullOrBlank() && it.subBad != "1"
            }
            for (iso1 in spokenIso1) {
                val code = LanguageMatcher.toIso6392B(iso1)
                val inLanguage = usable.filter { subtitle ->
                    subtitle.subLanguageID.equals(code, ignoreCase = true) ||
                        subtitle.iso639.equals(iso1, ignoreCase = true)
                }
                if (inLanguage.isEmpty()) continue
                return inLanguage.sortedWith(
                    compareBy<OpenSubtitles.Subtitle> { it.subHearingImpaired == "1" }
                        .thenBy { it.subAutoTranslation == "1" }
                        .thenByDescending { it.subDownloadsCnt?.toLongOrNull() ?: 0L }
                        .thenByDescending { it.score ?: 0.0 },
                ).first()
            }
            return null
        }
    }
}
