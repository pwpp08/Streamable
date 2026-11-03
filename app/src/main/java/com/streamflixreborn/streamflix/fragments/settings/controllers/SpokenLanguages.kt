package com.streamflixreborn.streamflix.fragments.settings.controllers

/** Pure list logic behind "Languages I speak": selection order is preference order. */
object SpokenLanguages {

    /** ISO-639-1 codes offered in the picker. */
    val choices = listOf(
        "en", "hu", "de", "fr", "es", "it", "pl", "pt", "ro", "cs", "sk",
        "hr", "sr", "ru", "uk", "tr", "nl", "sv", "ja", "ko", "zh", "ar",
    )

    val default = listOf("en", "hu")

    /** Checking appends to the end (lowest priority); unchecking removes. */
    fun toggle(order: List<String>, code: String, checked: Boolean): List<String> =
        if (checked) (order - code) + code else order - code

    /** Moves [code] to the front, adding it when missing. */
    fun makePrimary(order: List<String>, code: String): List<String> = listOf(code) + (order - code)

    /** Normalises stored values: lowercase, known codes first in stored order, no duplicates. */
    fun sanitize(order: List<String>): List<String> =
        order.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().ifEmpty { default }

    /** "English (primary), Hungarian, German" given a name lookup and a primary formatter. */
    fun summary(order: List<String>, name: (String) -> String, primary: (String) -> String): String =
        order.mapIndexed { index, code -> if (index == 0) primary(name(code)) else name(code) }
            .joinToString(", ")
}
