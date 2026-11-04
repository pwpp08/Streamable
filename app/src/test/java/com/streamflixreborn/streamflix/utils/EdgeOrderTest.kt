package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class EdgeOrderTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun fastConnectingEdgeComesFirstAndSlowOnesLast() {
        val slow = ip("188.114.96.12")
        val fast = ip("104.21.31.106")
        val middling = ip("172.67.176.56")
        val times = mapOf(slow to 1500L, fast to 21L, middling to 40L)
        assertEquals(listOf(fast, middling, slow), EdgeOrder.byLatency(listOf(slow, middling, fast), times))
    }

    @Test
    fun unreachableAddressesKeepTheirOrderAfterTheReachableOnes() {
        val a = ip("10.0.0.1")
        val b = ip("10.0.0.2")
        val c = ip("10.0.0.3")
        assertEquals(listOf(c, a, b), EdgeOrder.byLatency(listOf(a, b, c), mapOf(c to 30L)))
    }
}
