package com.localcast.poc.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IceCandidateQueueTest {

    @Test
    fun `candidates arriving before remote description are queued and flushed in FIFO order`() {
        val queue = IceCandidateQueue<String>()

        assertNull(queue.enqueueOrPassThrough("candidate:1 host 192.168.1.10"))
        assertNull(queue.enqueueOrPassThrough("candidate:2 host 192.168.1.10"))
        assertEquals(2, queue.pendingCount())

        val flushed = queue.onRemoteDescriptionApplied()
        assertEquals(
            listOf("candidate:1 host 192.168.1.10", "candidate:2 host 192.168.1.10"),
            flushed
        )
        assertEquals(0, queue.pendingCount())

        // Subsequent candidates pass through immediately
        val immediate = queue.enqueueOrPassThrough("candidate:3 host 192.168.1.10")
        assertEquals("candidate:3 host 192.168.1.10", immediate)
    }

    @Test
    fun `extractCandidateType identifies host srflx and relay candidates`() {
        val hostSdp = "candidate:55926 1 udp 2122260223 192.168.1.10 51234 typ host generation 0"
        assertEquals("host", PeerSession.extractCandidateType(hostSdp))
    }
}
