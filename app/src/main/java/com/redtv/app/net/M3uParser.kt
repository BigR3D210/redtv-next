package com.redtv.app.net

import com.redtv.app.model.Channel
import com.redtv.app.model.Section
import com.redtv.app.data.Identity
import java.net.URLDecoder

/** Parses M3U / M3U8 playlists and classifies each entry into a section by its group name. */
object M3uParser {

    private val attrRegex = Regex("([\\w-]+)=\"([^\"]*)\"")

    private val sportsKw = listOf("sport", "ppv", "espn", "dazn", "ufc", "nfl", "nba", "mlb",
        "nhl", "fight", "boxing", "wwe", "event", "racing", "soccer", "football", "tennis", "golf")
    private val movieKw = listOf("vod", "movie", "movies", "film", "cinema")
    private val seriesKw = listOf("series", "serie", "season", "show")

    private fun sectionFor(group: String): String {
        val g = group.lowercase()
        return when {
            sportsKw.any { g.contains(it) } -> Section.SPORTS
            seriesKw.any { g.contains(it) } -> Section.SERIES
            movieKw.any { g.contains(it) } -> Section.MOVIES
            else -> Section.LIVE
        }
    }

    fun parse(content: String): List<Channel> {
        val lines = content.lineSequence().map { it.trim() }.iterator()
        val out = ArrayList<Channel>()
        var pending: Pending? = null
        var idx = 0

        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> pending = parseExtInf(line)
                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    val grp = line.substringAfter(":", "").trim()
                    if (grp.isNotEmpty() && pending != null) pending = pending.copy(group = grp)
                }
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val key = line.substringAfter(":").substringBefore("=").lowercase()
                    val header = when(key) { "http-user-agent" -> "User-Agent"; "http-referrer", "http-referer" -> "Referer"; else -> null }
                    if(header != null) pending = pending?.let { it.copy(headers = it.headers + (header to line.substringAfter("="))) }
                }
                line.startsWith("#") -> {}
                else -> {
                    val p = pending
                    if (p != null) {
                        val group = p.group.ifBlank { "Uncategorized" }
                        out.add(
                            Channel(
                                id = "m3u_${Identity.hash(line)}",
                                name = p.name.ifBlank { "Channel ${idx + 1}" },
                                streamUrl = line.substringBefore('|'),
                                headers = p.headers + parseHeaders(line.substringAfter('|', "")),
                                logoUrl = p.logo,
                                category = group,
                                epgChannelId = p.tvgId,
                                section = sectionFor(group)
                            )
                        )
                        idx++
                        pending = null
                    }
                }
            }
        }
        return out
    }

    private fun parseHeaders(raw: String): Map<String,String> = raw.split('&').mapNotNull {
        val key = it.substringBefore('='); if(!it.contains('=') || !key.matches(Regex("[A-Za-z-]+"))) null
        else key to runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrDefault("")
    }.toMap()

    private data class Pending(val name: String, val tvgId: String?, val logo: String?, val group: String, val headers: Map<String,String> = emptyMap())

    private fun parseExtInf(line: String): Pending {
        val attrs = HashMap<String, String>()
        attrRegex.findAll(line).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
        var quoted = false
        val comma = line.indexOfFirst { c -> if (c == '"') quoted = !quoted; c == ',' && !quoted }
        val name = if(comma >= 0) line.substring(comma + 1).trim() else ""
        return Pending(
            name = if (name.isNotBlank()) name else (attrs["tvg-name"] ?: ""),
            tvgId = attrs["tvg-id"],
            logo = attrs["tvg-logo"],
            group = attrs["group-title"] ?: ""
        )
    }
}

