package com.streamflixreborn.streamflix.utils

import java.text.Normalizer
import java.util.Locale

/**
 * Turns the free-form language information found on tracks and subtitle labels
 * ("English (SDH)", "hun", "en-US", "Magyar", "Inglés", "Hungarian [CC]" ...) into
 * ISO-639-1 codes, so they can be compared with [UserPreferences.spokenLanguages].
 */
object LanguageMatcher {

    private val UNDETERMINED = setOf("und", "unknown", "mul", "zxx", "mis", "none", "default", "track")

    /** Java still reports a few withdrawn ISO-639-1 codes. */
    private val LEGACY_CODES = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "jw" to "jv", "mo" to "ro", "sh" to "sr")

    /** ISO-639-2/B codes that differ from the /T codes Java's Locale knows about. */
    private val BIBLIOGRAPHIC_TO_ISO1 = mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh",
        "cze" to "cs", "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de",
        "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
        "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
        "scc" to "sr", "scr" to "hr", "pob" to "pt", "pom" to "pt", "spn" to "es",
        "lat" to "la", "fil" to "tl",
    )

    private val ISO1_TO_BIBLIOGRAPHIC: Map<String, String> = mapOf(
        "sq" to "alb", "hy" to "arm", "eu" to "baq", "my" to "bur", "zh" to "chi",
        "cs" to "cze", "nl" to "dut", "fr" to "fre", "ka" to "geo", "de" to "ger",
        "el" to "gre", "is" to "ice", "mk" to "mac", "mi" to "mao", "ms" to "may",
        "fa" to "per", "ro" to "rum", "sk" to "slo", "bo" to "tib", "cy" to "wel",
    )

    /**
     * Names that streaming sites commonly use and that Locale display names
     * don't always cover (Hungarian/Spanish/Italian/German/French renderings).
     */
    private val ALIASES = mapOf(
        "english" to "en", "ingles" to "en", "inglese" to "en", "anglais" to "en", "englisch" to "en", "angol" to "en", "angielski" to "en",
        "hungarian" to "hu", "magyar" to "hu", "ungherese" to "hu", "hongrois" to "hu", "ungarisch" to "hu", "hungaro" to "hu", "wegierski" to "hu",
        "spanish" to "es", "espanol" to "es", "castellano" to "es", "castilian" to "es", "latino" to "es", "spanyol" to "es", "spagnolo" to "es", "espagnol" to "es", "spanisch" to "es",
        "german" to "de", "deutsch" to "de", "nemet" to "de", "tedesco" to "de", "allemand" to "de", "aleman" to "de",
        "french" to "fr", "francais" to "fr", "francia" to "fr", "frances" to "fr", "franzosisch" to "fr", "francese" to "fr",
        "italian" to "it", "italiano" to "it", "olasz" to "it", "italien" to "it", "italienisch" to "it",
        "portuguese" to "pt", "portugues" to "pt", "brazilian" to "pt", "portoghese" to "pt", "portugiesisch" to "pt",
        "russian" to "ru", "russkiy" to "ru", "orosz" to "ru",
        "japanese" to "ja", "nihongo" to "ja", "japonais" to "ja", "japones" to "ja",
        "korean" to "ko", "chinese" to "zh", "mandarin" to "zh", "cantonese" to "zh", "chinois" to "zh",
        "arabic" to "ar", "turkish" to "tr", "polish" to "pl", "dutch" to "nl", "flemish" to "nl",
        "czech" to "cs", "slovak" to "sk", "romanian" to "ro", "greek" to "el", "hebrew" to "he",
        "hindi" to "hi", "thai" to "th", "vietnamese" to "vi", "indonesian" to "id", "malay" to "ms",
        "swedish" to "sv", "norwegian" to "no", "danish" to "da", "finnish" to "fi", "ukrainian" to "uk",
        "croatian" to "hr", "serbian" to "sr", "bulgarian" to "bg", "slovenian" to "sl", "persian" to "fa", "farsi" to "fa",
        "tagalog" to "tl", "filipino" to "tl", "bengali" to "bn", "tamil" to "ta", "telugu" to "te", "urdu" to "ur",
    )

    /** Words that describe a track rather than its language. */
    private val NOISE = setOf(
        "cc", "sdh", "hi", "forced", "full", "sign", "signs", "songs", "default", "subtitle", "subtitles",
        "sub", "subs", "audio", "track", "stereo", "dub", "dubbed", "original", "commentary",
        "srt", "vtt", "ass", "ssa", "ttml", "closed", "captions", "caption", "hearing", "impaired",
    )

    private val NAME_INDEX: Map<String, String> by lazy { buildNameIndex() }
    private val ISO3_INDEX: Map<String, String> by lazy { buildIso3Index() }
    private val ISO1_CODES: Set<String> by lazy {
        Locale.getISOLanguages().map { LEGACY_CODES[it] ?: it }.toSet() + LEGACY_CODES.values
    }

    /** Returns the ISO-639-1 code for [raw], or null when no language can be recognised. */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = fold(raw)
        if (cleaned.isEmpty() || cleaned in UNDETERMINED) return null

        // Whole string as a code, e.g. "en", "eng", "en-US", "pt_BR", "es-419", "zh-Hans".
        codeOf(cleaned.split('-', '_').first())?.let { return it }

        val withoutQualifiers = fold(raw.replace(Regex("[\\[(].*?[])]"), " "))
        lookupName(withoutQualifiers)?.let { return it }
        lookupName(cleaned)?.let { return it }

        val tokens = cleaned.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        tokens.forEachIndexed { index, token ->
            if (token in NOISE || token in UNDETERMINED) return@forEachIndexed
            lookupName(token)?.let { return it }
            // 3-letter codes are distinctive enough anywhere; 2-letter ones only as the leading token.
            if (token.length == 3) ISO3_INDEX[token]?.let { return it }
            if (token.length == 2 && index == 0) codeOf(token)?.let { return it }
        }
        return null
    }

    /** Language of a track: prefer the declared language tag, fall back to its label. */
    fun detect(language: String?, label: String?): String? = normalize(language) ?: normalize(label)

    /** True when [language] is one of [spoken]. Unknown languages are never "foreign". */
    fun isSpoken(language: String?, spoken: Collection<String>): Boolean =
        language == null || spoken.any { it.equals(language, ignoreCase = true) }

    /** ISO-639-2/B code (what OpenSubtitles expects), e.g. "en" -> "eng", "de" -> "ger". */
    fun toIso6392B(iso1: String): String? {
        val code = iso1.lowercase()
        ISO1_TO_BIBLIOGRAPHIC[code]?.let { return it }
        return runCatching { Locale(code).isO3Language }.getOrNull()?.takeIf { it.length == 3 }
    }

    private fun codeOf(token: String): String? = when (token.length) {
        2 -> (LEGACY_CODES[token] ?: token).takeIf { it in ISO1_CODES }
        3 -> ISO3_INDEX[token]
        else -> null
    }

    private fun lookupName(text: String): String? {
        val key = text.trim().replace(Regex("\\s+"), " ")
        if (key.isEmpty()) return null
        return ALIASES[key] ?: NAME_INDEX[key]
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .trim()

    private fun buildIso3Index(): Map<String, String> {
        val index = HashMap<String, String>()
        for (code in Locale.getISOLanguages()) {
            val iso1 = LEGACY_CODES[code] ?: code
            runCatching { Locale(code).isO3Language }.getOrNull()
                ?.takeIf { it.length == 3 }
                ?.let { index.putIfAbsent(it, iso1) }
        }
        index.putAll(BIBLIOGRAPHIC_TO_ISO1)
        return index
    }

    private fun buildNameIndex(): Map<String, String> {
        val index = HashMap<String, String>()
        for (code in Locale.getISOLanguages()) {
            val iso1 = LEGACY_CODES[code] ?: code
            val locale = Locale(code)
            listOf(locale.getDisplayLanguage(Locale.ENGLISH), locale.getDisplayLanguage(locale))
                .map(::fold)
                .filter { it.isNotEmpty() && it != code }
                .forEach { index.putIfAbsent(it, iso1) }
        }
        return index
    }
}
