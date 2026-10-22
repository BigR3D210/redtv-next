package com.redtv.app.data

import com.redtv.app.model.*
import org.junit.Assert.*
import org.junit.Test

class SportsNowTest {
    private val channel=Channel("ppv","UFC PPV 1","http://example.test/live",category="US| PPV",section=Section.SPORTS)
    private val event=EpgProgram("id","UFC main card",100,200,isLive=true)
    @Test fun livePpvRequiresCurrentTiming() {
        assertTrue(SportsNow.isLiveNow(channel,event,100))
        assertTrue(SportsNow.isPpv(channel))
        assertFalse(SportsNow.isLiveNow(channel,event,99))
        assertFalse(SportsNow.isLiveNow(channel,event,200))
        assertFalse(SportsNow.isLiveNow(channel,null,150))
    }
    @Test fun staleProviderLabelIsNotConfirmation() {
        val label=channel.copy(name="Live | Boxing main event")
        assertTrue(SportsNow.providerLive(label))
        assertFalse(SportsNow.isLiveNow(label,null,150))
        assertFalse(SportsNow.providerLive(label.copy(name="ENDED | Live | Boxing")))
    }
    @Test fun excludesAlwaysOnAndReplayEvenWhenMarkedLive() {
        assertFalse(SportsNow.isLiveNow(channel.copy(category="US| 24/7 PPV"),event,150))
        assertFalse(SportsNow.isLiveNow(channel,event.copy(title="Live: UFC Replay"),150))
        assertFalse(SportsNow.isLiveNow(channel,event.copy(title="Live sports news"),150))
        assertFalse(SportsNow.isLiveNow(channel,event.copy(title="No event scheduled"),150))
    }
    @Test fun explicitLiveProgrammeTitleIsSupportedWithoutXmlFlag() {
        assertTrue(SportsNow.isLiveNow(channel,event.copy(title="LIVE: Team A vs Team B",isLive=false),150))
        assertFalse(SportsNow.isLiveNow(channel,event.copy(isLive=false),150))
    }
}
