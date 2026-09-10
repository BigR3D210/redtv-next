package com.redtv.app.data

import com.redtv.app.model.*
import org.junit.Assert.*
import org.junit.Test

class CatalogCacheTest {
    private class Store : TextStore {
        val values = mutableMapOf<String,String>()
        var largest = 0
        var fail = false
        override fun read(name: String) = values[name]
        override fun remove(name: String) { values.remove(name) }
        override fun write(name: String, value: String) {
            if (fail && name != "catalog") error("Disk full")
            largest = maxOf(largest, value.length)
            values[name] = value
        }
    }
    @Test fun largeLibraryRoundTripsWithoutLargeEncryptionInputs() {
        val store = Store()
        val cache = CatalogCache(store)
        val channels = List(50000) { Channel("live_$it", "Channel $it", "https://example.test/$it", description = "x".repeat(400)) }
        val catalog = CatalogCache.Catalog(SourceProfile("a", "A"), RemoteConfig(), channels, channels.take(5))
        cache.write("catalog", catalog)
        assertTrue("Encryption input must stay small: ${store.largest}", store.largest < 256 * 1024)
        assertEquals(catalog, cache.read("catalog"))
        cache.write("catalog", catalog.copy(channels = channels.take(2), episodes = emptyList()))
        assertEquals(2, store.values.size)
        assertEquals(channels.take(2), cache.read("catalog")!!.channels)
    }
    @Test fun interruptedReplacementPreservesPreviousLibrary() {
        val store = Store()
        val cache = CatalogCache(store)
        val original = CatalogCache.Catalog(SourceProfile("a", "A"), RemoteConfig(), listOf(Channel("1", "One", "https://example.test")), emptyList())
        cache.write("catalog", original)
        store.fail = true
        try { cache.write("catalog", original); fail("Expected write failure") } catch (_: IllegalStateException) { }
        assertEquals(original, cache.read("catalog"))
        assertEquals(2, store.values.size)
    }
}
