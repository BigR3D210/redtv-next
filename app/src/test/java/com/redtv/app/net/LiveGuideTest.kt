package com.redtv.app.net

import com.redtv.app.data.SportsNow
import com.redtv.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LiveGuideTest {
    @Test fun xmlLiveAndRepeatFlagsAreRespected() {
        val now=System.currentTimeMillis()
        val format=SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US)
        val start=format.format(Date(now-60000));val end=format.format(Date(now+60000))
        val xml="""<tv>
            <programme channel="a" start="$start" stop="$end"><title>Boxing main event</title><live/></programme>
            <programme channel="b" start="$start" stop="$end"><title>Live: Boxing main event</title><live/><previously-shown/></programme>
            <programme channel="c" start="$start" stop="$end"><title>Boxing</title></programme>
        </tv>"""
        val guide=EpgParser.parse(xml)
        val channel=Channel("a","PPV 1","http://example.test",category="US| PPV",section=Section.SPORTS)
        assertTrue(SportsNow.isLiveNow(channel,guide["a"]!!.single(),now))
        assertFalse(SportsNow.isLiveNow(channel,guide["b"]!!.single(),now))
        assertFalse(SportsNow.isLiveNow(channel,guide["c"]!!.single(),now))
    }
    @Test fun automaticGuideUsesTheSameServerAndEscapesCredentials() {
        assertEquals("https://example.test/xmltv.php?username=a%2Bb&password=x%26y",
            XtreamClient.guideUrl(Source(type="xtream",host="https://example.test/",username="a+b",password="x&y")))
    }
}
