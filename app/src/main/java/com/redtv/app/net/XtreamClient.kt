package com.redtv.app.net

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.redtv.app.model.Channel
import com.redtv.app.model.Section
import com.redtv.app.model.Source
import java.net.URLEncoder

/** Xtream Codes API client: Live (+ Sports split), Movies (VOD), and Series. */
object XtreamClient {
    fun guideUrl(source:Source):String {
        val ctx=ctx(source,Http.userAgent)
        return "${ctx.host}/xmltv.php?username=${enc(ctx.user)}&password=${enc(ctx.pass)}"
    }

    private val sportsKeywords = listOf(
        "sport", "ppv", "pay per view", "pay-per-view", "espn", "dazn", "ufc", "nfl",
        "nba", "mlb", "nhl", "fight", "boxing", "wwe", "wrestling", "event", "racing",
        "formula", " f1", "soccer", "football", "fifa", "tennis", "golf", "rugby",
        "cricket", "nascar", "motogp", "fanatiz", "willow"
    )

    private fun isSports(category: String?): Boolean {
        val c = (category ?: "").lowercase()
        return sportsKeywords.any { c.contains(it) }
    }

    /** Live + Sports + Movies in one shot (the grid-able tiles). */
    fun load(source: Source, agent: String = Http.userAgent): List<Channel> {
        val ctx = ctx(source, agent)
        val out = ArrayList<Channel>()
        out += loadLive(ctx)
        runCatching { out += loadVod(ctx) }
        return out
    }

    /** Series folders (each opens to its episodes). */
    fun loadSeries(source: Source, agent: String = Http.userAgent): List<Channel> {
        val ctx = ctx(source, agent)
        val cats = runCatching { catMap(get("${ctx.base}&action=get_series_categories", ctx.agent)) }.getOrDefault(emptyMap())
        val arr = getArray("${ctx.base}&action=get_series", ctx.agent)
        val out = ArrayList<Channel>(arr.size())
        for (e in arr) {
            if (!e.isJsonObject) continue
            val o = e.asJsonObject
            val id = o.str("series_id") ?: continue
            out.add(
                Channel(
                    id = "series_$id", sourceId = id, description = o.str("plot"),
                    name = o.str("name") ?: "Series $id",
                    streamUrl = "",
                    logoUrl = o.str("cover"),
                    category = cats[o.str("category_id")] ?: "Series",
                    section = Section.SERIES
                )
            )
        }
        return out
    }

    /** Episodes inside a series, ready to play. */
    fun loadEpisodes(source: Source, seriesId: String, agent: String = Http.userAgent): List<Channel> {
        val ctx = ctx(source, agent)
        val json = get("${ctx.base}&action=get_series_info&series_id=${enc(seriesId)}", ctx.agent)
        val root = JsonParser.parseString(json).asJsonObject
        val episodes = root.get("episodes") ?: return emptyList()
        val episodeLists = if(episodes.isJsonObject) episodes.asJsonObject.entrySet().associate { it.key to it.value }
            else if(episodes.isJsonArray) mapOf("1" to episodes) else emptyMap()
        val out = ArrayList<Channel>()
        for ((season, list) in episodeLists) {
            if (!list.isJsonArray) continue
            for (e in list.asJsonArray) {
                if (!e.isJsonObject) continue
            val o = e.asJsonObject
                val id = o.str("id") ?: continue
                val ext = o.str("container_extension") ?: "mp4"
                val title = o.str("title") ?: "Episode $id"
                out.add(
                    Channel(
                        id = "ep_$id", sourceId = id, seriesId = seriesId, season = season.toIntOrNull(), episode = o.str("episode_num")?.toIntOrNull(),
                        description = o.obj("info")?.str("plot"), durationSeconds = o.obj("info")?.str("duration_secs")?.toLongOrNull(),
                        name = "S${season} • $title",
                        streamUrl = "${ctx.host}/series/${enc(ctx.user)}/${enc(ctx.pass)}/${enc(id)}.${enc(ext)}",
                        logoUrl = o.obj("info")?.str("movie_image"),
                        section = Section.SERIES
                    )
                )
            }
        }
        return out.sortedWith(compareBy<Channel> { it.season ?: 0 }.thenBy { it.episode ?: Int.MAX_VALUE }.thenBy { it.sourceId.toLongOrNull() ?: Long.MAX_VALUE })
    }

    // ---- internals ----

    private class Ctx(val host: String, val user: String, val pass: String, val base: String, val agent: String)

    private fun ctx(source: Source, agent: String): Ctx {
        val host = normalizeHost(source.host ?: error("Missing host"))
        val user = source.username ?: error("Missing username")
        val pass = source.password ?: error("Missing password")
        val base = "$host/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        return Ctx(host, user, pass, base, agent)
    }

    private fun loadLive(ctx: Ctx): List<Channel> {
        val cats = runCatching { catMap(get("${ctx.base}&action=get_live_categories", ctx.agent)) }.getOrDefault(emptyMap())
        val streams = getArray("${ctx.base}&action=get_live_streams", ctx.agent)
        val out = ArrayList<Channel>(streams.size())
        for (e in streams) {
            if (!e.isJsonObject) continue
            val o = e.asJsonObject
            val id = o.str("stream_id") ?: continue
            val ext = o.str("container_extension") ?: "m3u8"
            val cat = cats[o.str("category_id")] ?: "Live TV"
            out.add(
                Channel(
                    id = "live_$id", sourceId = id,
                    name = o.str("name") ?: "Channel $id",
                    streamUrl = "${ctx.host}/live/${enc(ctx.user)}/${enc(ctx.pass)}/${enc(id)}.${enc(ext)}",
                    logoUrl = o.str("stream_icon"),
                    category = cat,
                    epgChannelId = o.str("epg_channel_id"),
                    number = o.str("num")?.toIntOrNull(),
                    section = if (isSports(cat)) Section.SPORTS else Section.LIVE
                )
            )
        }
        return out
    }

    private fun loadVod(ctx: Ctx): List<Channel> {
        val cats = runCatching { catMap(get("${ctx.base}&action=get_vod_categories", ctx.agent)) }.getOrDefault(emptyMap())
        val streams = getArray("${ctx.base}&action=get_vod_streams", ctx.agent)
        val out = ArrayList<Channel>(streams.size())
        for (e in streams) {
            if (!e.isJsonObject) continue
            val o = e.asJsonObject
            val id = o.str("stream_id") ?: continue
            val ext = o.str("container_extension") ?: "mp4"
            out.add(
                Channel(
                    id = "movie_$id", sourceId = id, description = o.str("plot"),
                    name = o.str("name") ?: "Movie $id",
                    streamUrl = "${ctx.host}/movie/${enc(ctx.user)}/${enc(ctx.pass)}/${enc(id)}.${enc(ext)}",
                    logoUrl = o.str("stream_icon") ?: o.str("cover"),
                    category = cats[o.str("category_id")] ?: "VOD",
                    section = Section.MOVIES
                )
            )
        }
        return out
    }

    private fun catMap(json: String): Map<String, String> {
        val map = HashMap<String, String>()
        runCatching {
            JsonParser.parseString(json).asJsonArray.forEach {
                val o = it.asJsonObject
                val id = o.str("category_id") ?: return@forEach
                map[id] = o.str("category_name") ?: id
            }
        }
        return map
    }

    private fun get(url: String, agent: String): String = Http.getString(url, agent)

    private fun getArray(url: String, agent: String): JsonArray {
        val el = JsonParser.parseString(get(url, agent))
        return if (el.isJsonArray) el.asJsonArray else JsonArray()
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(key: String): String? {
        val v = get(key) ?: return null
        if (v.isJsonNull) return null
        return runCatching { v.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun normalizeHost(raw: String): String {
        var h = raw.trim().trimEnd('/')
        if (!h.startsWith("http://", true) && !h.startsWith("https://", true)) h = "http://$h"
        return h
    }
}



