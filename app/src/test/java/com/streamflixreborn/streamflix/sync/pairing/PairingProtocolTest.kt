package com.streamflixreborn.streamflix.sync.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingProtocolTest {

    @Test
    fun uriRoundTrip() {
        val token = PairingCrypto.newToken()
        val uri = PairingProtocol.PairingUri("192.168.1.42", 41234, token)
        val text = uri.format()
        assertEquals(true, text.startsWith("streamflix://pair?h=192.168.1.42&p=41234&t="))
        assertEquals(true, text.endsWith("&v=1"))
        assertEquals(uri, PairingProtocol.PairingUri.parse(text))
    }

    @Test
    fun uriRejectsInvalidInput() {
        val token = PairingCrypto.base64Url(PairingCrypto.newToken())
        assertNull(PairingProtocol.PairingUri.parse("https://example.com"))
        assertNull(PairingProtocol.PairingUri.parse("streamflix://pair?h=192.168.1.2&p=80&t=$token&v=2"))
        assertNull(PairingProtocol.PairingUri.parse("streamflix://pair?h=evil.example&p=80&t=$token&v=1"))
        assertNull(PairingProtocol.PairingUri.parse("streamflix://pair?h=192.168.1.2&p=0&t=$token&v=1"))
        assertNull(PairingProtocol.PairingUri.parse("streamflix://pair?h=192.168.1.2&p=80&t=abc&v=1"))
    }

    @Test
    fun credentialsJsonRoundTrip() {
        val password = "p\"a\\ssé\n".toCharArray()
        val credentials = PairingProtocol.Credentials(
            supabaseUrl = "https://abc.supabase.co",
            publicKey = "sb_publishable_x",
            email = "me@example.com",
            password = password.copyOf(),
            profileName = "Vince",
            sourceDeviceName = "Pixel 8",
        )
        val bytes = credentials.toJsonBytes()
        val parsed = PairingProtocol.Credentials.fromJsonBytes(bytes)
        assertEquals("https://abc.supabase.co", parsed.supabaseUrl)
        assertEquals("sb_publishable_x", parsed.publicKey)
        assertEquals("me@example.com", parsed.email)
        assertArrayEquals(password, parsed.password)
        assertEquals("Vince", parsed.profileName)
        assertEquals("Pixel 8", parsed.sourceDeviceName)

        credentials.wipe()
        assertEquals(true, credentials.password.all { it == '\u0000' })
    }

    @Test
    fun sealedCredentialsSurviveTheWire() {
        val token = PairingCrypto.newToken()
        val credentials = PairingProtocol.Credentials(
            "https://abc.supabase.co", "key", "me@example.com", "pw".toCharArray(), null, null,
        )
        val sealed = PairingCrypto.seal(PairingCrypto.keyFromToken(token), credentials.toJsonBytes())
        val request = PairingProtocol.Request(
            mode = PairingProtocol.MODE_TOKEN,
            nonce = PairingCrypto.base64Url(sealed.nonce),
            ciphertext = PairingCrypto.base64Url(sealed.ciphertext),
        )
        val json = PairingProtocol.json.encodeToString(PairingProtocol.Request.serializer(), request)
        val decoded = PairingProtocol.json.decodeFromString(PairingProtocol.Request.serializer(), json)
        val plaintext = PairingCrypto.open(
            PairingCrypto.keyFromToken(token),
            PairingCrypto.fromBase64Url(decoded.nonce),
            PairingCrypto.fromBase64Url(decoded.ciphertext),
        )
        val parsed = PairingProtocol.Credentials.fromJsonBytes(plaintext)
        assertEquals("me@example.com", parsed.email)
        assertArrayEquals("pw".toCharArray(), parsed.password)
        assertNull(parsed.profileName)
    }
}
