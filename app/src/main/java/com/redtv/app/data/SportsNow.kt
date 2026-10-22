package com.redtv.app.data

import com.redtv.app.model.Channel
import com.redtv.app.model.EpgProgram

/** A provider channel name alone never confirms that an event is currently live. */
object SportsNow {
    const val LIVE_NOW="Live now"
    const val PPV="PPV / event channels"
    const val PROVIDER_LIVE="Provider live (unverified)"
    private val live=Regex("\\blive\\b",RegexOption.IGNORE_CASE)
    private val alwaysOn=Regex("24\\s*[/x-]\\s*7",RegexOption.IGNORE_CASE)
    private val notEvent=Regex("\\b(replay|rerun|repeat|highlights?|news|sportscenter|ended|upcoming|offline|off air|no events?|no programming|to be announced|tba)\\b",RegexOption.IGNORE_CASE)
    private val ppv=Regex("\\b(ppv|pay.per.view|ufc|boxing|wwe|wrestling|fight|event)\\b",RegexOption.IGNORE_CASE)
    fun isPpv(channel:Channel)=ppv.containsMatchIn(channel.category+" "+channel.name) && !alwaysOn.containsMatchIn(channel.category+" "+channel.name)
    fun providerLive(channel:Channel):Boolean {
        val text=channel.name
        return live.containsMatchIn(text) && !notEvent.containsMatchIn(text) && !alwaysOn.containsMatchIn(channel.category+" "+text)
    }
    fun isLiveNow(channel:Channel,programme:EpgProgram?,now:Long):Boolean {
        if(programme==null || programme.isRepeat || now !in programme.startMillis until programme.stopMillis)return false
        if(alwaysOn.containsMatchIn(channel.category+" "+channel.name))return false
        if(programme.title.isBlank() || notEvent.containsMatchIn(programme.title))return false
        return programme.isLive || live.containsMatchIn(programme.title)
    }
}
