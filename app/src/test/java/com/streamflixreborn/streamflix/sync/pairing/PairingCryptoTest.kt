package com.streamflixreborn.streamflix.sync.pairing

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairingCryptoTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val okm = PairingCrypto.hkdfSha256(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun tokenRoundTrip() {
        val token = PairingCrypto.newToken()
        val key = PairingCrypto.keyFromToken(token)
        val plaintext = "{\"email\":\"a@b.c\",\"password\":\"p\\\"w\"}".toByteArray()
        val sealed = PairingCrypto.seal(key, plaintext)
        assertEquals(PairingCrypto.NONCE_BYTES, sealed.nonce.size)
        assertArrayEquals(plaintext, PairingCrypto.open(PairingCrypto.keyFromToken(token), sealed.nonce, sealed.ciphertext))
    }

    @Test
    fun codeRoundTrip() {
        val nonce = PairingCrypto.randomBytes(PairingCrypto.NONCE_BYTES)
        val key = PairingCrypto.keyFromCode("042917", nonce)
        val sealed = PairingCrypto.seal(key, "hello".toByteArray(), nonce)
        val opened = PairingCrypto.open(PairingCrypto.keyFromCode("042917", nonce), sealed.nonce, sealed.ciphertext)
        assertEquals("hello", String(opened))
    }

    @Test
    fun wrongTokenFails() {
        val sealed = PairingCrypto.seal(PairingCrypto.keyFromToken(PairingCrypto.newToken()), "secret".toByteArray())
        expectBadTag { PairingCrypto.open(PairingCrypto.keyFromToken(PairingCrypto.newToken()), sealed.nonce, sealed.ciphertext) }
    }

    @Test
    fun wrongCodeFails() {
        val nonce = PairingCrypto.randomBytes(PairingCrypto.NONCE_BYTES)
        val sealed = PairingCrypto.seal(PairingCrypto.keyFromCode("123456", nonce), "secret".toByteArray(), nonce)
        expectBadTag { PairingCrypto.open(PairingCrypto.keyFromCode("123457", nonce), sealed.nonce, sealed.ciphertext) }
    }

    @Test
    fun tamperedCiphertextFails() {
        val key = PairingCrypto.keyFromToken(PairingCrypto.newToken())
        val sealed = PairingCrypto.seal(key, "secret payload".toByteArray())
        val tampered = sealed.ciphertext.copyOf().also { it[3] = (it[3].toInt() xor 0x01).toByte() }
        expectBadTag { PairingCrypto.open(key, sealed.nonce, tampered) }
    }

    @Test
    fun tamperedNonceFails() {
        val key = PairingCrypto.keyFromToken(PairingCrypto.newToken())
        val sealed = PairingCrypto.seal(key, "secret payload".toByteArray())
        val nonce = sealed.nonce.copyOf().also { it[0] = (it[0].toInt() xor 0x80).toByte() }
        expectBadTag { PairingCrypto.open(key, nonce, sealed.ciphertext) }
    }

    @Test
    fun codeIsSixDigitsAndHashIsShort() {
        repeat(50) {
            val code = PairingCrypto.newCode()
            assertTrue(code, code.length == 6 && code.all(Char::isDigit))
        }
        assertEquals(4, PairingCrypto.codeHash("123456").length)
        assertEquals(PairingCrypto.codeHash("123456"), PairingCrypto.codeHash("123456"))
        assertNotEquals(PairingCrypto.codeHash("123456"), PairingCrypto.codeHash("654321"))
    }

    @Test
    fun base64UrlRoundTrip() {
        for (size in 0..40) {
            val data = PairingCrypto.randomBytes(size)
            val text = PairingCrypto.base64Url(data)
            assertTrue(text.none { it == '+' || it == '/' || it == '=' })
            assertArrayEquals(data, PairingCrypto.fromBase64Url(text))
        }
        assertEquals("_-8", PairingCrypto.base64Url(byteArrayOf(0xFF.toByte(), 0xEF.toByte())))
    }

    private fun expectBadTag(block: () -> Unit) {
        try {
            block()
            fail("Expected AEADBadTagException")
        } catch (_: AEADBadTagException) {
        }
    }
}
