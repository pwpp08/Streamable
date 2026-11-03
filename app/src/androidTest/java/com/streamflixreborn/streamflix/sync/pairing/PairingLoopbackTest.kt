package com.streamflixreborn.streamflix.sync.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the TV-side [PairingServer] and the phone-side [PairingClient] against each other over
 * the device's real network interface. Supabase sign-in is replaced by a fake, so this checks
 * transport, encryption and the server state machine without an account.
 */
@RunWith(AndroidJUnit4::class)
class PairingLoopbackTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun credentials(password: String) = PairingProtocol.Credentials(
        supabaseUrl = "https://example.supabase.co",
        publicKey = "sb_publishable_test",
        email = "tester@example.com",
        password = password.toCharArray(),
        profileName = "Default",
        sourceDeviceName = "Test phone",
    )

    private fun startServer(
        authenticate: suspend (PairingProtocol.Credentials) -> String,
    ): Pair<PairingServer, PairingServer.Info> = runBlocking {
        val server = PairingServer(context, "Test TV", authenticate) { }
        server.start(scope)
        val waiting = withTimeout(10_000) {
            server.state.first { it is PairingServer.State.Waiting || it is PairingServer.State.Failed }
        }
        assertTrue("server did not start: $waiting", waiting is PairingServer.State.Waiting)
        server to (waiting as PairingServer.State.Waiting).info
    }

    @Test
    fun qrPairingDeliversCredentialsToTv() = runBlocking {
        var received: PairingProtocol.Credentials? = null
        var receivedPassword: CharArray? = null
        val (server, info) = startServer { creds ->
            received = creds
            receivedPassword = creds.password.copyOf()
            creds.email
        }
        val uri = PairingProtocol.PairingUri.parse(info.uri)!!

        val result = PairingClient.pair(uri, credentials("s3cret-pass"))

        assertEquals(PairingClient.Result.Success("Test TV"), result)
        assertEquals("tester@example.com", received?.email)
        assertEquals("https://example.supabase.co", received?.supabaseUrl)
        assertEquals("Default", received?.profileName)
        assertArrayEquals("s3cret-pass".toCharArray(), receivedPassword)
        val paired = withTimeout(5_000) { server.state.first { it is PairingServer.State.Paired } }
        assertEquals(PairingServer.State.Paired("tester@example.com"), paired)
    }

    @Test
    fun wrongTokenIsRejected() = runBlocking {
        var called = false
        val (server, info) = startServer { called = true; it.email }
        val real = PairingProtocol.PairingUri.parse(info.uri)!!
        val forged = real.copy(token = PairingCrypto.newToken())

        val result = PairingClient.pair(forged, credentials("x"))

        assertTrue("expected rejection, got $result", result is PairingClient.Result.Failure)
        assertTrue("authenticate must not run for a bad key", !called)
        assertTrue(server.state.value is PairingServer.State.Waiting)
        server.stop()
    }

    @Test
    fun signInErrorIsReportedToPhone() = runBlocking {
        val (server, info) = startServer { error("Invalid login credentials") }
        val uri = PairingProtocol.PairingUri.parse(info.uri)!!

        val result = PairingClient.pair(uri, credentials("wrong"))

        assertTrue("expected rejection, got $result", result is PairingClient.Result.Failure)
        result as PairingClient.Result.Failure
        assertEquals(PairingClient.Reason.REJECTED, result.reason)
        assertTrue(result.message.orEmpty().contains("Invalid login credentials"))
        server.stop()
    }
}
