package com.redtv.app.net

import com.google.gson.Gson
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.model.RemoteConfig
import com.redtv.app.model.Source
import com.redtv.app.model.SourceProfile
import fi.iki.elonen.NanoHTTPD


class ConfigServer(port: Int, private val prefs: Prefs, code: String,
    private val editorHtml: String, private val onPlay: (String, String, Boolean) -> Unit,
    private val onSaved: () -> Unit) : NanoHTTPD(port) {
    private val auth = LanAuth(code)
    private val gson = Gson()
    @Volatile private var profileId = prefs.activeProfile()?.id
    override fun stop() { auth.clear(); super.stop() }
    override fun serve(session: IHTTPSession): Response = try {
        val token = session.headers["cookie"].orEmpty().split(';').map { it.trim() }.firstOrNull { it.startsWith("redtv=") }?.removePrefix("redtv=")
        val identity = auth.validate(token)
        when {
            session.method == Method.GET && session.uri == "/" -> response(Response.Status.OK, editorHtml, "text/html; charset=utf-8")
            session.method == Method.POST && session.uri == "/pair" -> {
                val p = body(session)
                val paired = auth.pair(p["code"].orEmpty())
                if (paired == null) response(Response.Status.FORBIDDEN, "Pairing failed. Restart editor after ten attempts or ten minutes.")
                else response(Response.Status.OK, "Paired").apply { addHeader("Set-Cookie", "redtv=${paired.token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800") }
            }
            identity == null || prefs.activeProfile()?.id != profileId -> response(Response.Status.FORBIDDEN, "Pair with the code on your TV.")
            session.method == Method.GET && session.uri == "/api" -> {
                val channels = prefs.orderedChannels(ContentRepository.allChannels()).map { original ->
                    val ch = prefs.displayChannel(original)
                    mapOf("id" to ch.id, "name" to ch.name, "category" to ch.category,
                        "favorite" to prefs.isFavorite(ch.id), "hidden" to prefs.isHidden(ch.id))
                }
                response(Response.Status.OK, gson.toJson(mapOf("csrf" to identity.csrf, "channels" to channels, "sort" to prefs.sortMode,
                    "sourceName" to prefs.activeProfile()?.name, "sourceType" to (prefs.activeProfile()?.manual?.source ?: prefs.cachedConfig?.source)?.type)), "application/json")
            }
            session.method == Method.POST -> {
                if (session.headers["x-csrf-token"] != identity.csrf) response(Response.Status.FORBIDDEN, "Session rejected")
                else mutate(session)
            }
            else -> response(Response.Status.NOT_FOUND, "Not found")
        }
    } catch (_: Exception) { response(Response.Status.BAD_REQUEST, "Request could not be applied. Check the fields and try again.") }

    private fun body(session: IHTTPSession): Map<String, String> {
        val size = session.headers["content-length"]?.toIntOrNull()
        require(size != null && size in 1..65536 && !session.headers.containsKey("transfer-encoding"))
        require(session.headers["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded"))
        session.parseBody(HashMap())
        return session.parms
    }
    @Synchronized private fun mutate(session: IHTTPSession): Response {
        val p = body(session)
        require(prefs.activeProfile()?.id == profileId)
        when (session.uri) {
            "/sort" -> { require(p["mode"] in listOf("custom", "az", "za", "number", "category")); prefs.sortMode = p["mode"]!! }
            "/channels" -> {
                val ids = p["ids"].orEmpty().split('\n').filter { it.isNotEmpty() }
                require(ids.isNotEmpty() && ids.size <= 500)
                val valid = ContentRepository.allChannels().map { it.id }.toSet()
                require(ids.all { it in valid })
                when (p["action"]) {
                    "favorite", "hidden" -> { require(p["value"] in listOf("true", "false")); ids.forEach {
                        if (p["action"] == "favorite") prefs.setFavorite(it, p["value"] == "true") else prefs.setHidden(it, p["value"] == "true")
                    } }
                    "name" -> { require(ids.size == 1); prefs.setChannelName(ids.first(), p["value"].orEmpty().take(200)) }
                    "category" -> ids.forEach { prefs.setChannelCategory(it, p["value"].orEmpty().take(100)) }
                    "up", "down" -> { require(ids.size == 1); prefs.moveChannel(ids.first(), if (p["action"] == "up") -1 else 1, prefs.orderedChannels(ContentRepository.allChannels()).map { it.id }) }
                    else -> error("Invalid action")
                }
            }
            "/play" -> { val url = p["url"].orEmpty().trim(); require(RemoteSourceEdit.validUrl(url)); onPlay(url, p["name"].orEmpty().take(200).ifBlank { "Laptop media" }, p["kind"] == "live") }
            "/source" -> {
                val old = prefs.activeProfile(); val config = old?.manual ?: ContentRepository.config ?: prefs.cachedConfig ?: RemoteConfig(appName = "Red TV"); val existing = config.source
                val source = RemoteSourceEdit.apply(existing, p)
                prefs.upsertProfile(SourceProfile(old?.id ?: "p_${System.currentTimeMillis()}", p["name"].orEmpty().take(100).ifBlank { old?.name ?: "Laptop source" }, manual = config.copy(source = source)))
                profileId = prefs.activeProfile()?.id
            }
            else -> return response(Response.Status.NOT_FOUND, "Not found")
        }
        onSaved()
        return response(Response.Status.OK, "Saved to TV")
    }
    private fun response(status: Response.Status, value: String, type: String = "text/plain; charset=utf-8") = newFixedLengthResponse(status, type, value).apply {
        addHeader("Connection", "close"); addHeader("Cache-Control", "no-store"); addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Referrer-Policy", "no-referrer"); addHeader("X-Frame-Options", "DENY")
        addHeader("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
    }
}








