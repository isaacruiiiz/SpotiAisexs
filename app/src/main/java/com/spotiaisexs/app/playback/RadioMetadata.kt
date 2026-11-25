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

/**
 * Release year, tempo and style of a song, when a catalogue knows them.
 * [genres] are Deezer's genres for the song's album, lowercased ("pop",
 * "rap/hip hop", "reggaeton", "dance"…); `null` means they were never looked
 * up (facts saved by older versions), empty means Deezer has none.
 */
@Serializable
data class TrackFacts(val year: Int? = null, val bpm: Int? = null, val genres: List<String>? = null)

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
    private val albumGenres = ConcurrentHashMap<String, List<String>>()
    private val paceLock = Any()
    private var nextCallAt = 0L

    private fun key(title: String, artist: String) = RadioRecommender.historyKey(title, artist)

    fun cachedFacts(title: String, artist: String): TrackFacts? = facts[key(title, artist)]

    /** Year and tempo for as many of [tracks] as [budgetMs] allows; cached ones are free. */
    suspend fun factsFor(tracks: List<Pair<String, String>>, budgetMs: Long): Map<String, TrackFacts> {
        val missing = tracks.distinctBy { key(it.first, it.second) }.filter { facts[key(it.first, it.second)]?.genres == null }
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
        if (facts[k]?.genres != null) return
        runCatching {
            val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?]"""), " ").trim().ifBlank { title }
            val mainArtist = artist.split(",", "&", " feat", " ft", " x ").first().trim().ifBlank { artist }
            val searchUrl = "https://api.deezer.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", "artist:\"$mainArtist\" track:\"$cleanTitle\"")
                .addQueryParameter("limit", "1")
                .build()
            val id = getJson(searchUrl.toString())
                .let { it["data"] as? JsonArray }
                ?.firstOrNull()
                ?.let { (it as? JsonObject)?.get("id") as? JsonPrimitive }
                ?.content
            if (id == null) {
                // Remember the miss so the same song is not looked up on every batch.
                facts[k] = TrackFacts(genres = emptyList())
                return
            }
            val track = getJson("https://api.deezer.com/track/$id")
            val year = (track["release_date"] as? JsonPrimitive)?.content?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
            val bpm = (track["bpm"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
            val albumId = ((track["album"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content
            val genres = albumId?.let { album ->
                albumGenres[album] ?: run {
                    val data = getJson("https://api.deezer.com/album/$album")["genres"]
                        ?.let { (it as? JsonObject)?.get("data") as? JsonArray }
                    data.orEmpty()
                        .mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.trim()?.lowercase() }
                        .filter { it.isNotBlank() }
                        .also { albumGenres[album] = it }
                }
            }.orEmpty()
            facts[k] = TrackFacts(year = year, bpm = bpm, genres = genres)
        }.onFailure { Log.d(TAG, "Deezer lookup failed for $title", it) }
        // A failure (no network, Deezer's per-second quota) is not saved: it is tried again next time.
    }

    /**
     * A Deezer answer, or an exception. Deezer reports errors (its quota of
     * about 50 calls every 5 seconds above all) inside a normal answer; they
     * must not be taken for "this song is not in Deezer".
     */
    private fun getJson(url: String): JsonObject {
        pace()
        return client.newCall(Request.Builder().url(url).header("Accept-Language", "en").build()).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) throw java.io.IOException("Deezer HTTP ${response.code}")
            val obj = json.parseToJsonElement(body).jsonObject
            if ("error" in obj) throw java.io.IOException("Deezer error: ${obj["error"]}")
            obj
        }
    }

    /** Keeps calls to Deezer under its quota, however many lookups run at once. */
    private fun pace() {
        val wait = synchronized(paceLock) {
            val now = System.nanoTime() / 1_000_000
            val at = maxOf(now, nextCallAt)
            nextCallAt = at + DEEZER_GAP_MS
            at - now
        }
        if (wait > 0) Thread.sleep(wait)
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
                wanted.filter { !tags.containsKey(it.lowercase()) }
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
        const val DEEZER_GAP_MS = 115L
    }
}
