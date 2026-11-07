package com.streamflixreborn.streamflix.fragments.queue

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueStoreMergeTest {

    private fun doc(json: String) = JSONObject(json)

    private fun JSONObject.reaction(provider: String, key: String): String? =
        optJSONObject(provider)?.optJSONObject("r")?.optString(key)?.takeIf { it.isNotEmpty() }

    private fun JSONObject.seenKeys(provider: String): Set<String> =
        optJSONObject(provider)?.optJSONObject("s")?.keys()?.asSequence()?.toSet().orEmpty()

    @Test
    fun whatEachDeviceAddedSurvivesTheMerge() {
        val phone = doc("""{"TMDb":{"r":{"m:1":"LOVED"},"rt":{"m:1":100},"s":{"m:5":{"t":"A","at":50}}}}""")
        val tv = doc("""{"TMDb":{"r":{"m:2":"OKAY"},"rt":{"m:2":120},"s":{"m:6":{"t":"B","at":60}}}}""")
        val merged = QueueStore.merge(phone, tv)
        assertEquals("LOVED", merged.reaction("TMDb", "m:1"))
        assertEquals("OKAY", merged.reaction("TMDb", "m:2"))
        assertEquals(setOf("m:5", "m:6"), merged.seenKeys("TMDb"))
    }

    @Test
    fun theNewerReactionWinsInBothDirections() {
        val older = doc("""{"TMDb":{"r":{"m:1":"LIKED"},"rt":{"m:1":100}}}""")
        val newer = doc("""{"TMDb":{"r":{"m:1":"HATED"},"rt":{"m:1":200}}}""")
        assertEquals("HATED", QueueStore.merge(older, newer).reaction("TMDb", "m:1"))
        assertEquals("HATED", QueueStore.merge(newer, older).reaction("TMDb", "m:1"))
    }

    @Test
    fun aClearedReactionIsNotBroughtBackByAnOlderCopy() {
        val cleared = doc("""{"TMDb":{"r":{},"rt":{"m:1":300}}}""")
        val stale = doc("""{"TMDb":{"r":{"m:1":"LOVED"},"rt":{"m:1":100}}}""")
        assertEquals(null, QueueStore.merge(cleared, stale).reaction("TMDb", "m:1"))
        assertEquals(null, QueueStore.merge(stale, cleared).reaction("TMDb", "m:1"))
    }

    @Test
    fun aTitleUnmarkedAsSeenStaysGoneUntilItIsMarkedAgain() {
        val removed = doc("""{"TMDb":{"s":{},"sx":{"m:5":300}}}""")
        val stale = doc("""{"TMDb":{"s":{"m:5":{"t":"A","at":100}}}}""")
        assertFalse("m:5" in QueueStore.merge(removed, stale).seenKeys("TMDb"))
        assertFalse("m:5" in QueueStore.merge(stale, removed).seenKeys("TMDb"))

        val markedAgain = doc("""{"TMDb":{"s":{"m:5":{"t":"A","at":400}}}}""")
        assertTrue("m:5" in QueueStore.merge(removed, markedAgain).seenKeys("TMDb"))
    }

    @Test
    fun documentsFromBeforeTimestampsStillMergeAsAUnion() {
        val old = doc("""{"TMDb":{"r":{"m:1":"LIKED"},"s":{"m:5":{"t":"A","at":50}}}}""")
        val other = doc("""{"TMDb":{"r":{"m:2":"OKAY"}}}""")
        val merged = QueueStore.merge(old, other)
        assertEquals("LIKED", merged.reaction("TMDb", "m:1"))
        assertEquals("OKAY", merged.reaction("TMDb", "m:2"))
        assertEquals(setOf("m:5"), merged.seenKeys("TMDb"))
    }

    @Test
    fun mergingTwiceChangesNothingSoDevicesDoNotPingPong() {
        val a = doc("""{"TMDb":{"r":{"m:1":"LOVED"},"rt":{"m:1":100},"s":{"m:5":{"t":"A","at":50}}}}""")
        val b = doc("""{"TMDb":{"r":{"m:2":"OKAY"},"rt":{"m:2":120},"sx":{"m:9":10}}}""")
        val once = QueueStore.merge(a, b)
        val twice = QueueStore.merge(once, b)
        assertEquals(
            QueueStore.canonical(once).toString().length,
            QueueStore.canonical(twice).toString().length,
        )
        assertEquals(once.reaction("TMDb", "m:2"), twice.reaction("TMDb", "m:2"))
        assertEquals(once.seenKeys("TMDb"), twice.seenKeys("TMDb"))
    }
}
