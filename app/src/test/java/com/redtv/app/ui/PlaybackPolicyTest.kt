package com.redtv.app.ui

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun sustainedPlaybackRestoresRecoveryBudget() {
        val policy=PlaybackPolicy()
        repeat(3) { assertNotNull(policy.retryDelay()) }
        assertNull(policy.retryDelay())
        policy.stable()
        assertEquals(1000L,policy.retryDelay())
    }
    @Test fun retryBudgetStopsEvenIfAnUnstableStreamBrieflyBecameReady() {
        val policy = PlaybackPolicy()
        repeat(3) { assertNotNull(policy.retryDelay()); policy.ready() }
        assertNull(policy.retryDelay())
        policy.begin()
        assertNotNull(policy.retryDelay())
    }
    @Test fun historyRequiresReadyAndResetsForEachItem() {
        val policy = PlaybackPolicy()
        assertFalse(policy.canRecord)
        policy.ready()
        assertTrue(policy.canRecord)
        policy.begin()
        assertFalse(policy.canRecord)
    }
    @Test fun autoplayRequiresAdjacentEpisodeOfSameSeries() {
        assertTrue(PlaybackPolicy.sequential(true, "episode", "episode", "show", "show", 1, 2, 1, 3))
        assertFalse(PlaybackPolicy.sequential(false, "episode", "episode", "show", "show", 1, 2, 1, 3))
        assertFalse(PlaybackPolicy.sequential(true, "movie", "movie", null, null, null, null, null, null))
        assertFalse(PlaybackPolicy.sequential(true, "episode", "episode", "show", "other", 1, 2, 1, 3))
        assertFalse(PlaybackPolicy.sequential(true, "episode", "episode", "show", "show", 1, 2, 1, 4))
    }
}
