package com.redtv.app.net

import com.google.gson.Gson
import com.redtv.app.model.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test

class RoomHttpTest {
    @Test fun occupiedPortFallsBackToAvailablePort() {
        java.net.ServerSocket(0).use { occupied ->
            val server=RoomSyncServer.startAvailable(occupied.localPort)
            try {
                assertTrue(server.listeningPort > 0)
                assertNotEquals(occupied.localPort,server.listeningPort)
            } finally { server.stop() }
        }
    }
    @Test fun stateRequiresPairingAndPostAndNeverReturnsTokenInUrl() {
        val server = RoomSyncServer(0)
        server.start()
        try {
            val http = OkHttpClient()
            val base = "http://127.0.0.1:${server.listeningPort}"
            fun get(path: String, token: String = "") = http.newCall(Request.Builder().url(base + path).header("Authorization", "Bearer $token").build()).execute()
            get("/state").use { assertEquals(403, it.code) }
            get("/pair?code=${server.code}").use { assertEquals(403, it.code) }
            val request = Request.Builder().url("$base/pair").post("{\"code\":\"${server.code}\"}".toRequestBody("application/json".toMediaType())).build()
            val token = http.newCall(request).execute().use {
                assertEquals(200, it.code)
                Gson().fromJson(it.body!!.string(), Map::class.java)["token"] as String
            }
            server.publish(RoomState(Channel("test", "Title", "http://private/stream?secret=kept"), 100, true))
            get("/state", token).use { assertEquals(200, it.code); assertTrue(it.body!!.string().contains("Title")) }
            get("/state?token=$token").use { assertEquals(403, it.code) }
        } finally { server.stop() }
    }
}
