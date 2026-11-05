@file:Suppress("unused")

package android.text

object Html {
    const val FROM_HTML_MODE_LEGACY = 0

    @JvmStatic fun fromHtml(source: String?): CharSequence = org.jsoup.Jsoup.parse(source ?: "").text()
    @JvmStatic fun fromHtml(source: String?, flags: Int): CharSequence = fromHtml(source)
}
