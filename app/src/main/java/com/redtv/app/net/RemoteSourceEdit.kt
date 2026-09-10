package com.redtv.app.net

import com.redtv.app.model.Source
import java.net.URI

/** Blank input preserves secrets; changing source type requires the new type's endpoint. */
object RemoteSourceEdit {
    fun validUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        value.length <= 8192 && u.scheme in listOf("http", "https") && !u.host.isNullOrBlank()
    }.getOrDefault(false)
    fun apply(existing: Source, fields: Map<String, String>): Source {
        val type = fields["type"].orEmpty()
        require(type in listOf("m3u", "xtream"))
        fun retain(key: String, previous: String?) = fields[key]?.trim()?.takeIf { it.isNotEmpty() } ?: previous
        val sameType = type.equals(existing.type, true)
        val next = if (type == "m3u") Source(type, m3uUrl = retain("m3u", existing.m3uUrl.takeIf { sameType }), epgUrl = retain("epg", existing.epgUrl))
        else Source(type, host = retain("host", existing.host.takeIf { sameType }), username = retain("user", existing.username.takeIf { sameType }),
            password = retain("pass", existing.password.takeIf { sameType }), epgUrl = retain("epg", existing.epgUrl))
        require(validUrl(if (type == "m3u") next.m3uUrl.orEmpty() else next.host.orEmpty()))
        require(next.epgUrl.isNullOrBlank() || validUrl(next.epgUrl))
        if (type == "xtream") require(!next.username.isNullOrBlank() && !next.password.isNullOrBlank())
        return next
    }
}
