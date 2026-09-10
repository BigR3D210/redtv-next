package com.redtv.app.data

import com.google.gson.Gson
import com.redtv.app.model.*
import java.util.UUID

/** Never serialize or encrypt the complete provider library in one allocation. */
internal class CatalogCache(private val store: TextStore) {
    data class Catalog(val profile: SourceProfile, val config: RemoteConfig, val channels: List<Channel>, val episodes: List<Channel>)
    private data class Manifest(val profile: SourceProfile, val config: RemoteConfig, val chunks: List<String>, val episodeChunks: List<String>)
    private val gson = Gson()
    private fun manifest(key: String): Manifest? = store.read(key)?.let { gson.fromJson(it, Manifest::class.java) }

    fun read(key: String): Catalog? {
        val saved = manifest(key) ?: return null
        fun load(keys: List<String>): List<Channel> {
            val result = ArrayList<Channel>()
            keys.forEach { part ->
                val json = store.read(part) ?: error("Incomplete catalog cache")
                result.addAll(gson.fromJson(json, Array<Channel>::class.java))
            }
            return result
        }
        return Catalog(saved.profile, saved.config, load(saved.chunks), load(saved.episodeChunks))
    }

    fun write(key: String, catalog: Catalog) {
        val old = manifest(key)
        val created = mutableListOf<String>()
        val batch = UUID.randomUUID().toString()
        fun save(items: List<Channel>): List<String> {
            val names = mutableListOf<String>()
            var start = 0
            while (start < items.size) {
                val name = "${key}_${batch}_${created.size}"
                created.add(name)
                store.write(name, gson.toJson(items.subList(start, minOf(start + 128, items.size))))
                names.add(name)
                start += 128
            }
            return names
        }
        try {
            val chunks = save(catalog.channels)
            val episodes = save(catalog.episodes)
            store.write(key, gson.toJson(Manifest(catalog.profile, catalog.config, chunks, episodes)))
        } catch (e: Exception) {
            created.forEach { runCatching { store.remove(it) } }
            throw e
        }
        old?.let { (it.chunks + it.episodeChunks).forEach { part -> runCatching { store.remove(part) } } }
    }
}
