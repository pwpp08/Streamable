package com.streamflixreborn.streamflix.utils

import java.net.InetAddress

object EdgeOrder {
    /** Addresses ordered by measured connect time, fastest first; unreachable ones keep their place at the end. */
    fun byLatency(addresses: List<InetAddress>, connectMs: Map<InetAddress, Long>): List<InetAddress> =
        addresses.withIndex()
            .sortedWith(compareBy({ connectMs[it.value] ?: Long.MAX_VALUE }, { it.index }))
            .map { it.value }
}
