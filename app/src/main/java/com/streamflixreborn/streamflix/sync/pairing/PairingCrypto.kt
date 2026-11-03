package com.streamflixreborn.streamflix.sync.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto for TV pairing (javax.crypto only).
 *
 * - QR path: key = HKDF-SHA256(ikm = 128-bit token, salt = [HKDF_SALT], info = [HKDF_INFO]).
 * - Code path: key = PBKDF2-HMAC-SHA256(6-digit code, salt = nonce, [PBKDF2_ITERATIONS]).
 * - Cipher: AES-256-GCM, 96-bit nonce, 128-bit tag, AAD = [AAD].
 */
object PairingCrypto {
    const val TOKEN_BYTES = 16
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32
    const val PBKDF2_ITERATIONS = 100_000
    private const val TAG_BITS = 128

    private val HKDF_SALT = "streamflix-pair-v1".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO = "streamflix pairing aes-256-gcm key".toByteArray(Charsets.UTF_8)
    private val AAD = "streamflix-pair-v1".toByteArray(Charsets.UTF_8)

    private val random = SecureRandom()

    class Sealed(val nonce: ByteArray, val ciphertext: ByteArray)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun newToken(): ByteArray = randomBytes(TOKEN_BYTES)

    /** Uniformly random 6-digit code ("000000".."999999"). */
    fun newCode(): String = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000))

    fun keyFromToken(token: ByteArray): ByteArray = hkdfSha256(token, HKDF_SALT, HKDF_INFO, KEY_BYTES)

    fun keyFromCode(code: String, nonce: ByteArray): ByteArray {
        val chars = code.toCharArray()
        val spec = PBEKeySpec(chars, nonce, PBKDF2_ITERATIONS, KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    fun seal(key: ByteArray, plaintext: ByteArray, nonce: ByteArray = randomBytes(NONCE_BYTES)): Sealed {
        require(key.size == KEY_BYTES) { "AES-256 key required" }
        require(nonce.size == NONCE_BYTES) { "96-bit nonce required" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        return Sealed(nonce, cipher.doFinal(plaintext))
    }

    /** Decrypts and authenticates; throws [javax.crypto.AEADBadTagException] on a wrong key or tampering. */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256 key required" }
        require(nonce.size == NONCE_BYTES) { "96-bit nonce required" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Short hash of the 6-digit code advertised over NSD so the phone can pick
     * the right TV. Only 16 bits on purpose: about 15 codes share each value,
     * so the advertisement does not reveal the code.
     */
    fun codeHash(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("streamflix-code:" + code).toByteArray(Charsets.UTF_8))
        return "%02x%02x".format(digest[0], digest[1])
    }

    /** RFC 5869 HKDF with HMAC-SHA256. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        prk.fill(0)
        return out
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // Base64url without padding (RFC 4648 section 5), usable on every API level and in JVM tests.
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun base64Url(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            if (b1 >= 0) sb.append(ALPHABET[((b1 and 0x0F) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            if (b2 >= 0) sb.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    fun fromBase64Url(text: String): ByteArray {
        val clean = text.trim().trimEnd('=').replace('+', '-').replace('/', '_')
        require(clean.length % 4 != 1) { "Invalid base64url length" }
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val value = ALPHABET.indexOf(ch)
            require(value >= 0) { "Invalid base64url character" }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
