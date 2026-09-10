package com.redtv.app.net

import com.google.gson.Gson
import com.redtv.app.model.Channel
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.math.abs

 data class RoomState(val channel: Channel, val positionMs: Long, val playing: Boolean,
    val capturedAtEpochMs: Long = System.currentTimeMillis(), val programTimeMs: Long? = null)
private data class RoomEnvelope(val state: RoomState?, val serverTimeMs: Long)
object RoomSyncMath {
    fun targetPosition(state: RoomState, now: Long, followerWindowStartEpochMs: Long? = null): Long? {
        val advance = if (state.playing) (now - state.capturedAtEpochMs).coerceIn(0, 15_000) else 0L
        return if (state.channel.isVod) (state.positionMs + advance).coerceAtLeast(0)
        else if (state.programTimeMs != null && followerWindowStartEpochMs != null)
            (state.programTimeMs + advance - followerWindowStartEpochMs).coerceAtLeast(0) else null
    }
    fun needsSeek(current: Long, target: Long) = abs(current - target) > 1500
}
class RoomSyncServer(port: Int = 8091) : NanoHTTPD(port) {
    val code = LanAuth.newCode()
    private val auth = LanAuth(code)
    private val gson = Gson()
    @Volatile private var state: RoomState? = null
    fun publish(state: RoomState) { this.state = state }
    override fun stop() { auth.clear(); state = null; super.stop() }
    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.uri == "/pair" && session.method == Method.POST -> {
                val length = session.headers["content-length"]?.toIntOrNull()
                if (length == null || length !in 1..128 || session.headers.containsKey("transfer-encoding")) reply(Response.Status.BAD_REQUEST, "{}")
                else {
                    val files = HashMap<String, String>(); session.parseBody(files)
                    val code = gson.fromJson(files["postData"], Map::class.java)?.get("code") as? String ?: ""
                    val paired = auth.pair(code)
                    if (paired == null) reply(Response.Status.FORBIDDEN, "{}")
                    else reply(Response.Status.OK, gson.toJson(mapOf("token" to paired.token)))
                }
            }
            session.uri == "/state" && session.method == Method.GET &&
                auth.validate(session.headers["authorization"]?.removePrefix("Bearer ")) != null ->
                reply(Response.Status.OK, gson.toJson(RoomEnvelope(state, System.currentTimeMillis())))
            else -> reply(Response.Status.FORBIDDEN, "{}")
        }
    } catch (_: Exception) { reply(Response.Status.BAD_REQUEST, "{}") }
    private fun reply(status: Response.Status, body: String) = newFixedLengthResponse(status, "application/json", body).apply {
        addHeader("Cache-Control", "no-store"); addHeader("X-Content-Type-Options", "nosniff")
    }
}
class RoomSyncClient(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/').also {
        val uri = URI(it)
        require(uri.scheme == "http" && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty().isEmpty())
        // Explicit private IPv4 addresses only: no public hosts, DNS rebinding, or scanning.
        val p = uri.host.split('.').map { n -> n.toIntOrNull() ?: -1 }
        require(p.size == 4 && p.all { n -> n in 0..255 } && (p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)))
    }
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).followRedirects(false).build()
    private val gson = Gson()
    var lastRoundTripMs: Long = 0; private set
    fun pair(code: String): String {
        val request = Request.Builder().url("$base/pair").post(gson.toJson(mapOf("code" to code)).toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "Pairing failed. Check code or restart host sharing." }
            (gson.fromJson(r.body!!.string(), Map::class.java)["token"] as? String)?.takeIf { it.length == 64 } ?: error("Pairing failed")
        }
    }
    fun state(token: String): RoomState {
        val started = System.currentTimeMillis()
        return http.newCall(Request.Builder().url("$base/state").header("Authorization", "Bearer $token").build()).execute().use { r ->
            check(r.isSuccessful) { "Room unavailable. Pair again." }
            val envelope = gson.fromJson(r.body!!.string(), RoomEnvelope::class.java)
            val ended = System.currentTimeMillis(); lastRoundTripMs = ended - started
            val s = envelope.state ?: error("Host has not started playback")
            s.copy(capturedAtEpochMs = s.capturedAtEpochMs + (started + lastRoundTripMs / 2 - envelope.serverTimeMs))
        }
    }
}
