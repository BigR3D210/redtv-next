package com.redtv.app.net

import com.redtv.app.model.Source
import org.junit.Assert.*
import org.junit.Test

class RemoteSourceEditTest {
    @Test fun blankFieldsKeepCredentialsAndEndpoint() {
        val old = Source("xtream", host="http://192.168.1.2", username="person", password=" secret ", epgUrl="http://192.168.1.2/epg")
        assertEquals(old, RemoteSourceEdit.apply(old, mapOf("type" to "xtream", "host" to "", "user" to "", "pass" to "")))
        assertEquals("new", RemoteSourceEdit.apply(old, mapOf("type" to "xtream", "pass" to "new")).password)
    }
    @Test fun switchRequiresNewEndpoint() {
        val old = Source("xtream", host="http://host", username="u", password="p")
        assertThrows(IllegalArgumentException::class.java) { RemoteSourceEdit.apply(old, mapOf("type" to "m3u")) }
        val result = RemoteSourceEdit.apply(old, mapOf("type" to "m3u", "m3u" to "http://host/list.m3u"))
        assertNull(result.password)
        assertEquals("http://host/list.m3u", result.m3uUrl)
    }
    @Test fun unsafeAndMissingUrlsRejected() {
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "http://", "content://media/1"))
            assertFalse(RemoteSourceEdit.validUrl(url))
        assertTrue(RemoteSourceEdit.validUrl("https://example.org/video.mp4?key=abc"))
    }
}
