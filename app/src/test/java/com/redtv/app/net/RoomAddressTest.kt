package com.redtv.app.net

import org.junit.Assert.*
import org.junit.Test

class RoomAddressTest {
    @Test fun acceptsJustTheHostTvAddress() {
        assertEquals("http://192.168.1.249:8091",RoomAddress.normalize(" 192.168.1.249 "))
        assertEquals("http://192.168.1.249:8091",RoomAddress.normalize("http://192.168.1.249/"))
        assertEquals("http://10.0.0.2:9090",RoomAddress.normalize("10.0.0.2:9090"))
    }
    @Test fun rejectsPublicAddressesAndCredentials() {
        listOf("8.8.8.8","http://user@192.168.1.2","http://192.168.1.2/path","http://192.168.1.2?token=x","192.168.1.999","https://192.168.1.2").forEach {
            try { RoomAddress.normalize(it); fail("Accepted $it") } catch (_: IllegalArgumentException) { }
        }
    }
}
