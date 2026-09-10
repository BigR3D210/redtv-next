package com.redtv.app.net

import org.junit.Assert.*
import org.junit.Test
import com.redtv.app.model.Channel
import com.redtv.app.model.ContentKind

class LanSecurityTest {
    @Test fun pairingLocksOutAndExpires() {
        var time = 100L
        val auth = LanAuth("123456") { time }
        repeat(10) { assertNull(auth.pair("wrong")) }
        assertNull(auth.pair("123456"))
        val fresh = LanAuth("123456") { time }
        time += 600_001
        assertNull(fresh.pair("123456"))
    }
    @Test fun sessionRevokedAndExpires() {
        var time = 0L
        val auth = LanAuth("123456") { time }
        val session = auth.pair("123456")!!
        assertNotEquals(session.token, session.csrf)
        assertNotNull(auth.validate(session.token))
        time += 28_800_001
        assertNull(auth.validate(session.token))
        val fresh = LanAuth("123456")
        val token = fresh.pair("123456")!!.token
        fresh.clear()
        assertNull(fresh.validate(token))
    }
    @Test fun driftAndLiveTimeline() {
        val vod = RoomState(Channel("1", "movie", "http://a", kind = ContentKind.MOVIE), 10_000, true, 100_000)
        assertEquals(12_000L, RoomSyncMath.targetPosition(vod, 102_000))
        assertEquals(10_000L, RoomSyncMath.targetPosition(vod.copy(playing = false), 102_000))
        val live = vod.copy(channel = Channel("2", "live", "http://a"), programTimeMs = 200_000)
        assertEquals(22_000L, RoomSyncMath.targetPosition(live, 102_000, 180_000))
        assertNull(RoomSyncMath.targetPosition(live.copy(programTimeMs = null), 102_000, 180_000))
        assertFalse(RoomSyncMath.needsSeek(10_000, 11_000))
        assertTrue(RoomSyncMath.needsSeek(10_000, 12_000))
    }
}
