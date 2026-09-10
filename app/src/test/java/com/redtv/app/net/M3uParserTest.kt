package com.redtv.app.net

import org.junit.Assert.*
import org.junit.Test

class M3uParserTest {
    @Test fun qualityVariantsSharingGuideIdStayPlayableSeparately() {
        val items = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"news\",News HD\nhttps://example.test/hd\n#EXTINF:-1 tvg-id=\"news\",News SD\nhttps://example.test/sd")
        assertEquals(2, items.size)
        assertNotEquals(items[0].id, items[1].id)
        assertEquals(items[0].epgChannelId, items[1].epgChannelId)
    }
    @Test fun reorderDoesNotMoveFavoritesToAnotherChannel() {
        val a = "#EXTINF:-1,Alpha\nhttps://example.test/a\n"
        val b = "#EXTINF:-1,Beta\nhttps://example.test/b\n"
        assertEquals(M3uParser.parse(a+b).first().id, M3uParser.parse(b+a).last().id)
    }
    @Test fun displayNameCanContainCommas() {
        val ch = M3uParser.parse("#EXTINF:-1 group-title=\"Film, Drama\",Hello, World\nhttps://example.test/a").single()
        assertEquals("Hello, World", ch.name)
        assertEquals("Film, Drama", ch.category)
    }
    @Test fun headerDirectivesAndUrlHeadersAreRetained() {
        val ch = M3uParser.parse("#EXTINF:-1,Demo\n#EXTVLCOPT:http-user-agent=Red TV\nhttps://example.test/a|Referer=https%3A%2F%2Fexample.test%2F").single()
        assertEquals("https://example.test/a",ch.streamUrl)
        assertEquals("Red TV",ch.headers["User-Agent"])
        assertEquals("https://example.test/",ch.headers["Referer"])
    }
    @Test fun providerIdentitySeparatesSamePlaylist() {
        val id = M3uParser.parse("#EXTINF:-1,Demo\nhttps://example.test/a").single().id
        assertNotEquals(com.redtv.app.data.Identity.scoped("provider-a",id),com.redtv.app.data.Identity.scoped("provider-b",id))
        assertEquals(com.redtv.app.data.Identity.scoped("provider-a",id),com.redtv.app.data.Identity.scoped("provider-a",id))
    }}

