package com.spotiaisexs.app.playback

import android.content.Context
import android.util.Log
import com.spotiaisexs.app.data.generate.GenerateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Release year and tempo of a song, when a catalogue knows them. */
@Serializable
data class TrackFacts(val year: Int? = null, val bpm: Int? = null)

/**
 * Extra facts the radio switches need and YouTube does not give:
 *  - release year and tempo, from Deezer's public catalogue (no key needed);
 *  - artist tags and similar tracks, from Last.fm when the user added a key.
 *
 * Everything is best effort and cached. A missing answer is `null` / empty and
 * the radio treats it as "don't know", never as a reason to stop playing.
 */
@Singleton
class RadioMetadata @Inject constructor(
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
    private val generateRepository: dagger.Lazy<GenerateRepository>,
) {
    private val client = okHttpClient.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.applicationContext.getSharedPreferences("radio_metadata", Context.MODE_PRIVATE)
    private val factsSerializer = MapSerializer(String.serializer(), TrackFacts.serializer())
    private val gate = Semaphore(4)

    private val facts = ConcurrentHashMap<String, TrackFacts>().apply {
        runCatching {
            prefs.getString(KEY_FACTS, null)?.let { putAll(json.decodeFromString(factsSerializer, it)) }
        }
    }
    private val tags = ConcurrentHashMap<String, Set<String>>()

    private fun key(title: String, artist: String) = RadioRecommender.historyKey(title, artist)

    fun cachedFacts(title: String, artist: String): TrackFacts? = facts[key(title, artist)]

    /** Year and tempo for as many of [tracks] as [budgetMs] allows; cached ones are free. */
    suspend fun factsFor(tracks: List<Pair<String, String>>, budgetMs: Long): Map<String, TrackFacts> {
        val missing = tracks.distinctBy { key(it.first, it.second) }.filter { key(it.first, it.second) !in facts }
        if (missing.isNotEmpty()) {
            withTimeoutOrNull(budgetMs) {
                coroutineScope {
                    missing.map { (title, artist) ->
                        async(Dispatchers.IO) { gate.withPermit { fetchFacts(title, artist) } }
                    }.awaitAll()
                }
            }
            persist()
        }
        return tracks.mapNotNull { (title, artist) ->
            val k = key(title, artist)
            facts[k]?.let { k to it }
        }.toMap()
    }

    private fun fetchFacts(title: String, artist: String) {
        val k = key(title, artist)
        if (k in facts) return
        runCatching {
            val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?]"""), " ").trim().ifBlank { title }
            val mainArtist = artist.split(",", "&", " feat", " ft", " x ").first().trim().ifBlank { artist }
            val searchUrl = "https://api.deezer.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", "artist:\"$mainArtist\" track:\"$cleanTitle\"")
                .addQueryParameter("limit", "1")
                .build()
            val id = getJson(searchUrl.toString())
                ?.let { it["data"] as? JsonArray }
                ?.firstOrNull()
                ?.let { (it as? JsonObject)?.get("id") as? JsonPrimitive }
                ?.content
            if (id == null) {
                // Remember the miss so the same song is not looked up on every batch.
                facts[k] = TrackFacts()
                return
            }
            val track = getJson("https://api.deezer.com/track/$id") ?: return
            val year = (track["release_date"] as? JsonPrimitive)?.content?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
            val bpm = (track["bpm"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
            facts[k] = TrackFacts(year = year, bpm = bpm)
        }.onFailure { Log.d(TAG, "Deezer lookup failed for $title", it) }
    }

    private fun getJson(url: String): JsonObject? =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) null
            else json.parseToJsonElement(body).jsonObject.takeIf { "error" !in it }
        }

    private fun persist() {
        runCatching {
            val snapshot = if (facts.size > MAX_FACTS) facts.entries.take(MAX_FACTS).associate { it.key to it.value } else HashMap(facts)
            prefs.edit().putString(KEY_FACTS, json.encodeToString(factsSerializer, snapshot)).apply()
        }
    }

    /** Lowercased Last.fm tags of an artist; empty without a Last.fm key or on any error. */
    suspend fun artistTags(artist: String): Set<String> {
        val k = artist.trim().lowercase()
        if (k.isBlank()) return emptySet()
        tags[k]?.let { return it }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val result = generateRepository.get().call(
                    mapOf("method" to "artist.gettoptags", "artist" to artist, "autocorrect" to "1"),
                )
                val list = result["toptags"]?.jsonObject?.get("tag") as? JsonArray ?: return@runCatching emptySet<String>()
                list.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.trim()?.lowercase() }
                    .filter { it.isNotBlank() && it != "seen live" }
                    .take(10)
                    .toSet()
            }.getOrNull()
        }
        // Only a real answer is cached: a failed call (no key, no network) may work later.
        if (found != null) tags[k] = found
        return found.orEmpty()
    }

    suspend fun artistTagsFor(artists: Collection<String>, budgetMs: Long): Map<String, Set<String>> {
        val wanted = artists.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                wanted.filter { it.lowercase() !in tags }
                    .map { artist -> async { gate.withPermit { artistTags(artist) } } }
                    .awaitAll()
            }
        }
        return wanted.mapNotNull { artist -> tags[artist.lowercase()]?.let { artist.lowercase() to it } }.toMap()
    }

    /** Last.fm "similar tracks" as (title, artist); empty without a key. */
    suspend fun similarTracks(title: String, artist: String, limit: Int): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val result = generateRepository.get().call(
                    mapOf(
                        "method" to "track.getsimilar",
                        "track" to title,
                        "artist" to artist,
                        "autocorrect" to "1",
                        "limit" to (limit * 2).toString(),
                    ),
                )
                val list = result["similartracks"]?.jsonObject?.get("track") as? JsonArray ?: return@runCatching emptyList()
                list.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val name = (item["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
                    val by = ((item["artist"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.trim().orEmpty()
                    if (name.isBlank() || by.isBlank()) null else name to by
                }.take(limit)
            }.getOrDefault(emptyList())
        }

    private companion object {
        const val TAG = "RadioMetadata"
        const val KEY_FACTS = "facts"
        const val MAX_FACTS = 4000
    }
}
