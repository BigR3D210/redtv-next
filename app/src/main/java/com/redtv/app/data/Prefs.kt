package com.redtv.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.redtv.app.model.*

/** Preferences are source-scoped; private files containing URLs are encrypted. */
class Prefs(context: Context, private val store: TextStore = PrivateStore(context.applicationContext)) {
    private val sp = context.getSharedPreferences("redtv", Context.MODE_PRIVATE)
    private val gson = Gson()
    private fun scope(key: String) = "p_${activeProfileId ?: "local"}_$key"
    private fun privateKey(key: String) = Identity.hash(scope(key))
    fun profiles(): MutableList<SourceProfile> = synchronized(lock) {
        val old = sp.getString("profiles", null)
        val result = decode<List<SourceProfile>>(store.read("profiles") ?: old) ?: emptyList()
        if (old != null) { store.write("profiles", gson.toJson(result)); sp.edit().remove("profiles").remove("cached_config").apply() }
        result.toMutableList()
    }
    var activeProfileId: String?
        get() = sp.getString("active_profile", null)
        set(v) { sp.edit().putString("active_profile", v).apply() }
    fun activeProfile(): SourceProfile? = profiles().let { it.firstOrNull { p -> p.id == activeProfileId } ?: it.firstOrNull() }
    fun hasSource() = profiles().isNotEmpty()
    fun upsertProfile(profile: SourceProfile) = synchronized(lock) {
        val list = profiles(); val at = list.indexOfFirst { it.id == profile.id }
        if(at < 0) list.add(profile) else list[at] = profile
        store.write("profiles",gson.toJson(list)); activeProfileId = profile.id; ContentRepository.dirty = true
    }
    fun deleteProfile(id: String) = synchronized(lock) {
        val list = profiles().filterNot { it.id == id }; store.write("profiles",gson.toJson(list))
        if(activeProfileId == id) activeProfileId = list.firstOrNull()?.id
        ContentRepository.dirty = true
    }
    var hideDuplicates: Boolean
        get() = sp.getBoolean(scope("dedup"),false)
        set(v) { sp.edit().putBoolean(scope("dedup"),v).apply() }
    var autoPlay: Boolean
        get() = sp.getBoolean("autoplay",false)
        set(v) { sp.edit().putBoolean("autoplay",v).apply() }
    var resizeMode: Int
        get() = sp.getInt("resize",0)
        set(v) { sp.edit().putInt("resize",v).apply() }
    var subtitleEnabled: Boolean
        get() = sp.getBoolean("subtitles",false)
        set(v) { sp.edit().putBoolean("subtitles",v).apply() }
    var audioLanguage: String
        get() = sp.getString("audio_language","").orEmpty()
        set(v) { sp.edit().putString("audio_language",v).apply() }
    var subtitleLanguage: String
        get() = sp.getString("subtitle_language","").orEmpty()
        set(v) { sp.edit().putString("subtitle_language",v).apply() }
    var sortMode: String
        get() = sp.getString(scope("sort"),"custom").orEmpty()
        set(v) { sp.edit().putString(scope("sort"),v).apply() }
    private fun set(key: String) = HashSet(sp.getStringSet(scope(key),emptySet()) ?: emptySet())
    private fun changeSet(key: String,id: String,value: Boolean) = synchronized(lock) {
        val values = set(key); if(value) values.add(id) else values.remove(id)
        sp.edit().putStringSet(scope(key),values).apply()
    }
    fun favorites(): MutableSet<String> = set("favorites")
    fun isFavorite(id: String) = id in favorites()
    fun setFavorite(id: String,value: Boolean) = changeSet("favorites",id,value)
    fun toggleFavorite(id: String): Boolean = synchronized(lock) { val v = !isFavorite(id); setFavorite(id,v); v }
    fun hidden(): MutableSet<String> = set("hidden")
    fun isHidden(id: String) = id in hidden()
    fun setHidden(id: String,value: Boolean) = changeSet("hidden",id,value)
    fun toggleHidden(id: String) = synchronized(lock) { setHidden(id,!isHidden(id)) }
    fun clearHidden() { sp.edit().remove(scope("hidden")).apply() }
    private fun list(key: String): List<String> = decode(sp.getString(scope(key),null)) ?: emptyList()
    private fun saveList(key: String,items: List<String>) { sp.edit().putString(scope(key),gson.toJson(items)).apply() }
    fun pinned() = list("pinned")
    fun isPinned(id: String) = id in pinned()
    fun togglePinned(id: String) = synchronized(lock) {
        val pins = pinned().toMutableList(); if(!pins.remove(id)) pins.add(0,id); saveList("pinned",pins)
    }
    fun recents() = list("recents")
    fun pushRecent(id: String) = synchronized(lock) { saveList("recents",(listOf(id)+recents().filterNot { it == id }).take(300)) }
    var lastChannelId: String?
        get() = sp.getString(scope("last"),null)
        set(v) { sp.edit().putString(scope("last"),v).apply() }
    fun resumePosition(id: String) = sp.getLong(scope("resume_$id"),0L)
    fun setResumePosition(id: String,posMs: Long) {
        val edit = sp.edit(); if(posMs > 10000) edit.putLong(scope("resume_$id"),posMs) else edit.remove(scope("resume_$id")); edit.apply()
    }
    fun continueIds() = watchHistory().filter { it.channel.isVod && !it.completed && it.positionMs > 10000 }.map { it.channel.id }
    fun watchHistory(): List<WatchEntry> = synchronized(lock) { decode<List<WatchEntry>>(store.read(privateKey("history"))) ?: emptyList() }
    fun historyChannels() = watchHistory().map { it.channel }
    fun recordPlayback(channel: Channel,positionMs: Long,durationMs: Long,completed: Boolean = false) = synchronized(lock) {
        val done = completed || (channel.isVod && durationMs > 0 && positionMs >= durationMs - minOf(60000L,durationMs/20))
        val entry = WatchEntry(channel,if(done) 0 else positionMs.coerceAtLeast(0),durationMs.coerceAtLeast(0),System.currentTimeMillis(),done)
        store.write(privateKey("history"),gson.toJson((listOf(entry)+watchHistory().filterNot { it.channel.id == channel.id }).take(300)))
        if(channel.isVod) setResumePosition(channel.id,entry.positionMs)
        pushRecent(channel.id); lastChannelId = channel.id
    }
    fun removeHistory(id: String) = synchronized(lock) {
        store.write(privateKey("history"),gson.toJson(watchHistory().filterNot { it.channel.id == id }))
        setResumePosition(id,0); saveList("recents",recents().filterNot { it == id })
    }
    fun clearHistory() = synchronized(lock) {
        watchHistory().forEach { setResumePosition(it.channel.id,0) }; store.remove(privateKey("history")); saveList("recents",emptyList())
    }
    private fun overrides(key: String): Map<String,String> = decode(sp.getString(scope(key),null)) ?: emptyMap()
    private fun override(key: String,id: String,value: String) = synchronized(lock) {
        val map = overrides(key).toMutableMap(); if(value.isBlank()) map.remove(id) else map[id] = value.trim().take(160)
        sp.edit().putString(scope(key),gson.toJson(map)).apply()
    }
    fun setChannelName(id: String,name: String) = override("names",id,name)
    fun setChannelCategory(id: String,category: String) = override("groups",id,category)
    fun displayChannel(ch: Channel) = ch.copy(name=overrides("names")[ch.id] ?: ch.name,category=overrides("groups")[ch.id] ?: ch.category)
    fun setChannelOrder(ids: List<String>) { saveList("order",ids.distinct()); sortMode="custom" }
    fun moveChannel(id: String,delta: Int,visibleIds: List<String>) {
        val ids=visibleIds.toMutableList(); val from=ids.indexOf(id); if(from<0)return
        val to=(from+delta).coerceIn(0,ids.lastIndex); ids.removeAt(from); ids.add(to,id)
        setChannelOrder(ids+list("order").filterNot { it in ids })
    }
    fun orderedChannels(list: List<Channel>): List<Channel> {
        val names=overrides("names"); val groups=overrides("groups")
        val items=list.map { it.copy(name=names[it.id] ?: it.name,category=groups[it.id] ?: it.category) }
        val sorted=when(sortMode) {
            "az" -> items.sortedBy { it.name.lowercase() }
            "za" -> items.sortedByDescending { it.name.lowercase() }
            "number" -> items.sortedBy { it.number ?: Int.MAX_VALUE }
            "category" -> items.sortedWith(compareBy({it.category.lowercase()},{it.name.lowercase()}))
            else -> {val ranks=this.list("order").withIndex().associate {it.value to it.index};items.sortedBy {ranks[it.id] ?: Int.MAX_VALUE}}
        }
        val pins=pinned().withIndex().associate {it.value to it.index}
        return sorted.sortedBy {pins[it.id] ?: Int.MAX_VALUE}
    }
    var cachedConfig: RemoteConfig?
        get() = decode(store.read(privateKey("config")))
        set(v) { if(v == null)store.remove(privateKey("config")) else store.write(privateKey("config"),gson.toJson(v)) }
    fun migrateLegacy(channels: List<Channel>) = synchronized(lock) {
        if(sp.getBoolean("migration_v2",false))return@synchronized
        if(channels.isEmpty())return@synchronized
        fun oldId(ch:Channel):String = if(ch.id.substringAfter(':').startsWith("m3u_"))ch.epgChannelId.orEmpty() else ch.id.substringAfter(':')
        val map=channels.groupBy {oldId(it)}.filter {it.key.isNotBlank() && it.value.size==1}.mapValues {it.value.single().id}
        for(key in listOf("favorites","hidden")) {
            val ids=sp.getStringSet(key,emptySet()).orEmpty().mapNotNull {map[it]}.toSet()
            sp.edit().putStringSet(scope(key),set(key)+ids).apply()
        }
        for(key in listOf("pinned","recents"))saveList(key,(decode<List<String>>(sp.getString(key,null)) ?: emptyList()).mapNotNull {map[it]})
        channels.forEach {val pos=sp.getLong("resume_${oldId(it)}",0);if(pos>0 && chIsUnique(it,map))recordPlayback(it,pos,0)}
        sp.edit().putBoolean("migration_v2",true).apply()
    }
    private fun chIsUnique(ch:Channel,map:Map<String,String>)=ch.id in map.values
    private inline fun <reified T> decode(json: String?): T? = json?.let {runCatching {gson.fromJson<T>(it,object:TypeToken<T>(){}.type)}.getOrNull()}
    companion object {private val lock=Any()}
}

