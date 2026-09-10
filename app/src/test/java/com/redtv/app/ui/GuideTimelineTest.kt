package com.redtv.app.ui

import com.redtv.app.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideTimelineTest {
    @Test fun clipsProgramAndKeepsGapsAligned() {
        val programs=listOf(EpgProgram("x","Old",-10,20,null),EpgProgram("x","Next",40,120,null))
        val slots=GuideTimeline.slots(programs,0,100)
        assertEquals(listOf(0L to 20L,20L to 40L,40L to 100L),slots.map {it.start to it.end})
        assertNull(slots[1].program)
    }
    @Test fun emptyGuideStillHasASelectableChannelSlot() {
        assertEquals(1,GuideTimeline.slots(emptyList(),100,200).size)
    }
}
