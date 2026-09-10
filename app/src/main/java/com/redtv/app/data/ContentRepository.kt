package com.redtv.app.data

import android.content.Context
import com.google.gson.Gson
import com.redtv.app.model.*
import com.redtv.app.net.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

/** Catalog snapshots are committed only if their source profile is still active. */
object ContentRepository {
    @Volatile var live: List<Channel> = emptyList(); private set
    @Volatile var movies: List<Channel> = emptyList(); private set
    @Volatile var sports: List<Channel> = emptyList(); private set
    @Volatile var series: List<Channel> = emptyList(); private set
    @Volatile var epg: Map<String,List<EpgProgram>> = emptyMap(); private set
    @Volatile var config: RemoteConfig? = null; private set
    @Volatile var currentEpisodes: List<Channel> = emptyList()
    @Volatile var launchQueue: List<Channel> = emptyList()
    @Volatile var dirty = false
    private val lock = Any()
    private val gson = Gson()
    private var store: PrivateStore? = null
    private var provider = ""
    private var generation = 0L
    private var index: Map<String,Channel> = emptyMap()
    private val remembered = linkedMapOf<String,Channel>()
    private data class Snapshot(val profile: SourceProfile, val config: RemoteConfig, val channels: List<Channel>, val episodes: List<Channel>)
    fun initialize(context: Context) = synchronized(lock) {
        if(store == null) {
            store = PrivateStore(context.applicationContext)
            runCatching { gson.fromJson(store?.read("remembered"),Array<Channel>::class.java) }.getOrNull()?.forEach { remembered[it.id] = it }
        }
    }
    fun hasAny() = allChannels().isNotEmpty()
    fun allChannels(): List<Channel> = live + movies + sports + series
    fun sectionItems(section: String) = when(section) { Section.MOVIES -> movies; Section.SERIES -> series; Section.SPORTS -> sports; else -> live }
    fun categoriesFor(section: String) = sectionItems(section).map { it.category }.distinct().sortedBy { it.lowercase() }
    fun channelById(id: String): Channel? = synchronized(lock) { index[id] ?: remembered[id] ?: currentEpisodes.firstOrNull { it.id == id } ?: launchQueue.firstOrNull { it.id == id } }
    private fun assign(items: List<Channel>) {
        index = items.associateBy { it.id }
        live = items.filter { it.section == Section.LIVE }; sports = items.filter { it.section == Section.SPORTS }
        movies = items.filter { it.section == Section.MOVIES }; series = items.filter { it.section == Section.SERIES }
    }
    private fun scoped(items: List<Channel>, id: String) = items.map { ch -> if(ch.providerId == id) ch else ch.copy(id = Identity.scoped(id,ch.id), providerId = id) }
    fun restore(prefs: Prefs): Boolean = synchronized(lock) {
        val profile = prefs.activeProfile()
        if(profile == null) { generation++; provider = ""; assign(emptyList()); currentEpisodes = emptyList(); epg = emptyMap(); config = null; return@synchronized false }
        val snap = runCatching { gson.fromJson(store?.read("catalog_${Identity.hash(profile.id)}"), Snapshot::class.java) }.getOrNull()
        if(provider != profile.id) { generation++; provider = profile.id; assign(emptyList()); currentEpisodes = emptyList(); epg = emptyMap(); config = null }
        if(snap == null || snap.profile != profile) { generation++; assign(emptyList()); currentEpisodes = emptyList(); epg = emptyMap(); config = null; return@synchronized false }
        config = snap.config; assign(snap.channels); remembered.putAll(snap.episodes.associateBy { it.id }); currentEpisodes = snap.episodes
        prefs.migrateLegacy(snap.channels); true
    }
    private fun persist(profile: SourceProfile) {
        val cfg = config ?: return
        store?.write("catalog_${Identity.hash(profile.id)}", gson.toJson(Snapshot(profile,cfg,allChannels(),remembered.values.filter { it.providerId == profile.id })))
    }
    fun remember(channels: List<Channel>) = synchronized(lock) {
        channels.forEach { remembered[it.id] = it }
        // Single encrypted episode index permits queued/history episodes to resolve after process death.
        store?.write("remembered",gson.toJson(remembered.values.toList().takeLast(10000)))
    }
    fun createQueue(context: Context, items: List<Channel>): String = synchronized(lock) {
        initialize(context); val key = UUID.randomUUID().toString(); launchQueue = items.toList()
        store?.write("queue_$key",gson.toJson(items))
        val keys = runCatching { gson.fromJson(store?.read("queue_index"),Array<String>::class.java)?.toList().orEmpty() }.getOrDefault(emptyList()) + key
        keys.dropLast(20).forEach { if(it.matches(Regex("[a-fA-F0-9-]{36}"))) store?.remove("queue_$it") }
        store?.write("queue_index",gson.toJson(keys.takeLast(20))); items.forEach { remembered[it.id] = it }; key
    }
    fun readQueue(context: Context, key: String): List<Channel> = synchronized(lock) {
        initialize(context)
        if(!key.matches(Regex("[a-fA-F0-9-]{36}"))) return@synchronized emptyList()
        runCatching { gson.fromJson(store?.read("queue_$key"), Array<Channel>::class.java)?.toList().orEmpty() }.getOrDefault(emptyList()).also { launchQueue = it; it.forEach { ch -> remembered[ch.id] = ch } }
    }
    suspend fun reload(prefs: Prefs) = withContext(Dispatchers.IO) {
        val profile = prefs.activeProfile() ?: error("No source configured")
        val ticket = synchronized(lock) { ++generation }
        val cacheKey = "config_${Identity.hash(profile.id)}"
        val cfg = if(profile.configUrl.isNullOrBlank()) profile.manual ?: error("No source configured") else {
            runCatching { configForProfile(profile) }.getOrElse {
                val saved = runCatching { gson.fromJson(store?.read(cacheKey), Snapshot::class.java) }.getOrNull()
                if(saved?.profile == profile) saved.config else throw it
            }
        }
        val items = scoped(if(cfg.source.isXtream()) XtreamClient.load(cfg.source, cfg.userAgent ?: Http.userAgent) + runCatching { XtreamClient.loadSeries(cfg.source, cfg.userAgent ?: Http.userAgent) }.getOrDefault(emptyList())
            else M3uParser.parse(Http.getString(cfg.source.m3uUrl ?: error("No playlist address"),cfg.userAgent ?: Http.userAgent)),profile.id)
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if(ticket != generation || prefs.activeProfile() != profile) return@synchronized
            if(provider != profile.id) { currentEpisodes = emptyList(); epg = emptyMap() }
            provider = profile.id; config = cfg; assign(items); dirty = false
            store?.write(cacheKey,gson.toJson(Snapshot(profile,cfg,emptyList(),emptyList())))
            persist(profile); prefs.migrateLegacy(items)
        }
    }
    suspend fun refreshEpg(prefs: Prefs) = withContext(Dispatchers.IO) {
        val profile = prefs.activeProfile() ?: return@withContext
        val state = synchronized(lock) { if(provider != profile.id) null else config?.let { it to generation } } ?: return@withContext
        val url = state.first.source.epgUrl
        val data = if(url.isNullOrBlank()) emptyMap() else Http.withStream(url,state.first.userAgent ?: Http.userAgent) { EpgParser.parse(it) }
        currentCoroutineContext().ensureActive()
        synchronized(lock) { if(provider == profile.id && prefs.activeProfile() == profile && generation == state.second) epg = data }
    }
    suspend fun loadEpisodes(seriesId: String): List<Channel> = withContext(Dispatchers.IO) {
        val state = synchronized(lock) { Triple(config,provider,generation) }
        val cfg = state.first ?: return@withContext emptyList()
        val src = cfg.source
        val sourceId = channelById(seriesId)?.sourceId ?: seriesId.substringAfterLast(':').removePrefix("series_")
        val items = scoped(XtreamClient.loadEpisodes(src,sourceId, cfg.userAgent ?: Http.userAgent),state.second)
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if(provider != state.second || generation != state.third) return@synchronized emptyList<Channel>()
            currentEpisodes = items; remember(items); items
        }
    }
    suspend fun previewChannels(profile: SourceProfile): List<Channel> = withContext(Dispatchers.IO) {
        val cfg = configForProfile(profile)
        scoped(if(cfg.source.isXtream()) XtreamClient.load(cfg.source, cfg.userAgent ?: Http.userAgent) else M3uParser.parse(Http.getString(cfg.source.m3uUrl ?: error("No playlist address"),cfg.userAgent ?: Http.userAgent)),profile.id)
    }
    private fun configForProfile(profile: SourceProfile): RemoteConfig = if(!profile.configUrl.isNullOrBlank()) {
        try { gson.fromJson(Http.getString(profile.configUrl),RemoteConfig::class.java) ?: error("Empty source configuration") }
        catch(e: Exception) { error("Unable to load source configuration") }
    } else profile.manual ?: error("No source configured")
    fun nowAndNext(id: String?): Pair<EpgProgram?,EpgProgram?> {
        val list = epg[id] ?: return null to null; val now = System.currentTimeMillis()
        return list.firstOrNull { now in it.startMillis until it.stopMillis } to list.firstOrNull { it.startMillis >= now }
    }
}



