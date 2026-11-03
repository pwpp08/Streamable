package com.streamflixreborn.streamflix.sync.pairing

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.CharBuffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Wire format of TV pairing. Version 1. */
object PairingProtocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_streamflix._tcp"
    const val ATTR_CODE_HASH = "ch"
    const val ATTR_VERSION = "v"
    const val ATTR_NAME = "n"
    const val PATH = "/pair"
    const val MODE_TOKEN = "token"
    const val MODE_CODE = "code"

    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** `streamflix://pair?h=<ip>&p=<port>&t=<base64url token>&v=1` */
    data class PairingUri(val host: String, val port: Int, val token: ByteArray) {
        fun format(): String =
            "streamflix://pair?h=${enc(host)}&p=$port&t=${PairingCrypto.base64Url(token)}&v=$VERSION"

        override fun equals(other: Any?): Boolean =
            other is PairingUri && other.host == host && other.port == port && other.token.contentEquals(token)

        override fun hashCode(): Int = (host.hashCode() * 31 + port) * 31 + token.contentHashCode()

        companion object {
            fun parse(raw: String?): PairingUri? {
                val text = raw?.trim() ?: return null
                if (!text.startsWith("streamflix://pair?", ignoreCase = true)) return null
                val params = text.substringAfter('?').split('&').mapNotNull { part ->
                    val key = part.substringBefore('=', "")
                    if (key.isEmpty()) null else key to dec(part.substringAfter('=', ""))
                }.toMap()
                if ((params["v"]?.toIntOrNull() ?: return null) != VERSION) return null
                val host = params["h"]?.takeIf { isIpv4(it) } ?: return null
                val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                val token = runCatching { PairingCrypto.fromBase64Url(params["t"] ?: return null) }.getOrNull()
                    ?.takeIf { it.size == PairingCrypto.TOKEN_BYTES } ?: return null
                return PairingUri(host, port, token)
            }

            private fun isIpv4(value: String): Boolean {
                val parts = value.split('.')
                return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true && p.length <= 3 }
            }

            private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
            private fun dec(value: String) = URLDecoder.decode(value, "UTF-8")
        }
    }

    @Serializable
    data class Request(
        val v: Int = VERSION,
        val mode: String? = null,
        val nonce: String,
        val ciphertext: String,
    )

    @Serializable
    data class Response(
        val ok: Boolean,
        val deviceName: String? = null,
        val error: String? = null,
    )

    /**
     * Plaintext sent to the TV. The password is a CharArray so callers can
     * wipe it with [wipe] after use.
     */
    class Credentials(
        val supabaseUrl: String,
        val publicKey: String,
        val email: String,
        val password: CharArray,
        val profileName: String?,
        val sourceDeviceName: String?,
    ) {
        fun wipe() = password.fill('\u0000')

        /** UTF-8 JSON; the intermediate char buffer is wiped before returning. */
        fun toJsonBytes(): ByteArray {
            val sb = StringBuilder(256 + password.size * 2)
            sb.append('{')
            appendField(sb, "supabaseUrl", supabaseUrl.toCharArray()); sb.append(',')
            appendField(sb, "publicKey", publicKey.toCharArray()); sb.append(',')
            appendField(sb, "email", email.toCharArray()); sb.append(',')
            appendField(sb, "password", password); sb.append(',')
            appendField(sb, "profileName", profileName.orEmpty().toCharArray()); sb.append(',')
            appendField(sb, "sourceDeviceName", sourceDeviceName.orEmpty().toCharArray())
            sb.append('}')
            val chars = CharArray(sb.length).also { sb.getChars(0, sb.length, it, 0) }
            for (i in 0 until sb.length) sb.setCharAt(i, '\u0000')
            val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
            chars.fill('\u0000')
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (buffer.hasArray()) buffer.array().fill(0)
            return bytes
        }

        companion object {
            /** Parses decrypted plaintext; the caller wipes [bytes] afterwards. */
            fun fromJsonBytes(bytes: ByteArray): Credentials {
                val obj: JsonObject = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
                fun field(name: String): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                return Credentials(
                    supabaseUrl = field("supabaseUrl").orEmpty(),
                    publicKey = field("publicKey").orEmpty(),
                    email = field("email").orEmpty(),
                    password = field("password").orEmpty().toCharArray(),
                    profileName = field("profileName")?.takeIf { it.isNotBlank() },
                    sourceDeviceName = field("sourceDeviceName")?.takeIf { it.isNotBlank() },
                )
            }

            private fun appendField(sb: StringBuilder, name: String, value: CharArray) {
                sb.append('"').append(name).append("\":\"")
                for (c in value) {
                    when {
                        c == '"' -> sb.append("\\\"")
                        c == '\\' -> sb.append("\\\\")
                        c < ' ' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                        else -> sb.append(c)
                    }
                }
                sb.append('"')
            }
        }
    }
}
