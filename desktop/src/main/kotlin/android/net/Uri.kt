@file:Suppress("unused")

package android.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** The parts of android.net.Uri the shared extractors use, on top of java.net.URI. */
class Uri private constructor(private val raw: String) {

    private val uri: URI? = runCatching { URI(raw) }.getOrNull()
        ?: runCatching { URI(raw.replace(" ", "%20").replace("|", "%7C")) }.getOrNull()

    val scheme: String? get() = uri?.scheme
    val host: String? get() = uri?.host
    val port: Int get() = uri?.port ?: -1
    val path: String? get() = uri?.path
    val encodedPath: String? get() = uri?.rawPath
    val query: String? get() = uri?.query
    val encodedQuery: String? get() = uri?.rawQuery
    val fragment: String? get() = uri?.fragment
    val authority: String? get() = uri?.authority
    val pathSegments: List<String> get() = path?.split('/')?.filter { it.isNotEmpty() } ?: emptyList()
    val lastPathSegment: String? get() = pathSegments.lastOrNull()
    val queryParameterNames: Set<String> get() = params().map { it.first }.toCollection(LinkedHashSet())

    private fun params(): List<Pair<String, String>> =
        encodedQuery?.split('&')?.filter { it.isNotEmpty() }?.map {
            val i = it.indexOf('=')
            if (i < 0) decode(it) to "" else decode(it.substring(0, i)) to decode(it.substring(i + 1))
        } ?: emptyList()

    fun getQueryParameter(key: String): String? = params().firstOrNull { it.first == key }?.second
    fun getQueryParameters(key: String): List<String> = params().filter { it.first == key }.map { it.second }

    fun buildUpon(): Builder = Builder(this)

    override fun toString(): String = raw
    override fun equals(other: Any?) = other is Uri && other.raw == raw
    override fun hashCode() = raw.hashCode()

    class Builder internal constructor(base: Uri?) {
        private var scheme = base?.scheme
        private var authority = base?.authority
        private var path = base?.encodedPath
        private val query = mutableListOf<Pair<String, String>>().apply { base?.params()?.let(::addAll) }
        private var fragment = base?.fragment

        constructor() : this(null)

        fun scheme(value: String?) = apply { scheme = value }
        fun authority(value: String?) = apply { authority = value }
        fun encodedAuthority(value: String?) = apply { authority = value }
        fun path(value: String?) = apply { path = value }
        fun encodedPath(value: String?) = apply { path = value }
        fun appendPath(segment: String) = apply { path = (path ?: "").trimEnd('/') + "/" + encode(segment) }
        fun appendEncodedPath(segment: String) = apply { path = (path ?: "").trimEnd('/') + "/" + segment.trimStart('/') }
        fun appendQueryParameter(key: String, value: String?) = apply { query += key to (value ?: "") }
        fun clearQuery() = apply { query.clear() }
        fun encodedQuery(value: String?) = apply {
            query.clear()
            value?.let { parse("x://x?$it").params().let(query::addAll) }
        }
        fun fragment(value: String?) = apply { fragment = value }

        fun build(): Uri {
            val sb = StringBuilder()
            scheme?.let { sb.append(it).append(':') }
            authority?.let { sb.append("//").append(it) }
            path?.let { sb.append(it) }
            if (query.isNotEmpty()) sb.append('?').append(query.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" })
            fragment?.let { sb.append('#').append(it) }
            return Uri(sb.toString())
        }
    }

    companion object {
        @JvmField val EMPTY = Uri("")

        @JvmStatic fun parse(value: String): Uri = Uri(value)

        @JvmStatic
        fun encode(value: String?): String? =
            value?.let { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20").replace("%7E", "~").replace("*", "%2A") }

        @JvmStatic
        fun encode(value: String?, allow: String?): String? {
            var out = encode(value) ?: return null
            allow?.forEach { ch -> out = out.replace(URLEncoder.encode(ch.toString(), Charsets.UTF_8), ch.toString()) }
            return out
        }

        @JvmStatic
        fun decode(value: String?): String = value?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(it) } ?: ""

        @JvmStatic fun fromParts(scheme: String, ssp: String, fragment: String?): Uri =
            Uri("$scheme:$ssp" + (fragment?.let { "#$it" } ?: ""))
    }
}
