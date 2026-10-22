package com.redtv.app.data

import java.util.Locale

object BrowseGroups {
    private val prefix=Regex("^([A-Z]{2,3})(?:[^A-Z]|$)")
    fun country(group:String):String {
        val text=group.trim().trimStart('#',' ').uppercase(Locale.ROOT)
        if(text.startsWith("AT&T") || text.startsWith("UNITED STATES"))return "US"
        return when(val code=prefix.find(text)?.groupValues?.get(1).orEmpty()) {
            "USA" -> "US"
            "AUS" -> "AU"
            "CAN" -> "CA"
            "UK","GBR" -> "GB"
            else -> code
        }
    }
}
