package com.spotiaisexs.app.data.mood

import com.spotiaisexs.app.data.local.db.SongPlayStatsEntity
import com.spotiaisexs.app.data.music.InnerTubeMusicApi
import com.spotiaisexs.app.data.music.TextMatch
import com.spotiaisexs.app.data.music.YouTubeMusicTrack
import com.spotiaisexs.app.data.music.YouTubePlaylistResult
import com.spotiaisexs.app.data.music.YouTubePlaylistSummary
import com.spotiaisexs.app.data.repository.SongPlayStatsRepository
import com.spotiaisexs.app.data.thisis.ThisIsPlaylists
import com.spotiaisexs.app.playback.RadioFilters
import com.spotiaisexs.app.playback.RadioMetadata
import com.spotiaisexs.app.playback.RadioRecommender
import com.spotiaisexs.app.playback.TrackFacts
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A mood or kind of music with its own automatic playlist. */
enum class Mood(
    val id: String,
    val title: String,
    val subtitle: String,
    /** What to look for among YouTube Music's playlists. */
    val searches: List<String>,
    /** For the decades: the first year. */
    val decade: Int? = null,
) {
    GYM("gym", "Gym", "Para darlo todo", listOf("workout hits gym", "gym motivation songs")),
    MOTIVACION("motivacion", "Motivación", "Para venirte arriba", listOf("canciones motivación", "feel good hits")),
    CHILL("chill", "Chill", "Para bajar revoluciones", listOf("chill vibes", "chill pop relax")),
    FIESTA("fiesta", "Fiesta", "Para la previa", listOf("fiesta éxitos", "party hits")),
    POP_ES("pop-es", "Pop español", "Pop en español", listOf("pop español éxitos", "pop en español")),
    POP_EN("pop-en", "Pop inglés", "Pop en inglés", listOf("pop hits", "english pop hits")),
    ANOS_70("70s", "Años 70", "Lo mejor de los 70", listOf("70s hits", "éxitos años 70"), decade = 1970),
    ANOS_80("80s", "Años 80", "Lo mejor de los 80", listOf("80s hits", "éxitos años 80"), decade = 1980),
    ANOS_90("90s", "Años 90", "Lo mejor de los 90", listOf("90s hits", "éxitos años 90"), decade = 1990),
    ANOS_2000("2000s", "Años 2000", "Lo mejor de los 2000", listOf("2000s hits", "éxitos años 2000"), decade = 2000),
    ;

    companion object {
        fun byId(id: String): Mood? = entries.firstOrNull { it.id == id }
    }
}

/** A mood playlist and which of its songs come from the user's listening. */
data class MoodDetail(
    val mood: Mood,
    val playlist: YouTubePlaylistResult,
    val mineVideoIds: Set<String>,
)

/**
 * Mood playlists ("Gym", "Chill", "Pop español", "Años 80"…), built like
 * "Esto es": the user's own songs that fit the mood alternate with songs from
 * YouTube Music's playlists for it, without repeats, up to [MAX_TRACKS].
 *
 * Which of the user's songs fit is decided with what the radio already knows:
 * year and tempo from Deezer, the artist's Last.fm tags (only with a Last.fm
 * key) and the title's language. A song without evidence is left out rather
 * than guessed in. The playlist is rebuilt when opened, and kept for a while
 * so opening it again is instant.
 */
@Singleton
class MoodPlaylists @Inject constructor(
    private val stats: SongPlayStatsRepository,
    private val innerTube: InnerTubeMusicApi,
    private val metadata: RadioMetadata,
) {
    private val built = ConcurrentHashMap<Mood, Pair<Long, MoodDetail>>()

    suspend fun build(id: String): MoodDetail {
        val mood = parse(id) ?: throw java.io.IOException("Esta playlist no existe.")
        built[mood]?.let { (at, detail) -> if (System.currentTimeMillis() - at < KEEP_MS) return detail }
        return coroutineScope {
            val catalogJob = async { catalogSongs(mood) }
            val mineJob = async { userSongs(mood) }
            val catalog = catalogJob.await()
            val mine = mineJob.await()
            val tracks = mix(catalog.first, mine)
            if (tracks.isEmpty()) throw java.io.IOException("No hay canciones para ${mood.title} ahora mismo. Reintenta.")
            val mineKeys = mine.mapTo(HashSet()) { ThisIsPlaylists.songKey(it.title) }
            val detail = MoodDetail(
                mood = mood,
                playlist = YouTubePlaylistResult(
                    id = idFor(mood),
                    title = mood.title,
                    author = if (mine.isEmpty()) mood.subtitle else "${mood.subtitle}, con canciones tuyas",
                    artworkUrl = catalog.second ?: tracks.first().artworkUrl,
                    trackCount = tracks.size,
                    tracks = tracks,
                ),
                mineVideoIds = tracks.filter { ThisIsPlaylists.songKey(it.title) in mineKeys }.mapTo(HashSet()) { it.videoId },
            )
            built[mood] = System.currentTimeMillis() to detail
            detail
        }
    }

    /**
     * Songs from YouTube Music's own playlists for the mood (its official
     * ones first), one playlist after another a song at a time, and the cover
     * of the first one for the screen.
     */
    private suspend fun catalogSongs(mood: Mood): Pair<List<YouTubeMusicTrack>, String?> = coroutineScope {
        val found = mood.searches.map { query ->
            async { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { runCatching { innerTube.searchPlaylists(query, limit = 8) }.getOrNull() }.orEmpty() }
        }.awaitAll()
        val chosen = pickPlaylists(found.flatten())
        val lists = chosen.map { playlist ->
            async {
                withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    runCatching { innerTube.fetchPlaylist(playlist.id, maxTracks = 100) }.getOrNull()
                }?.tracks.orEmpty()
            }
        }.awaitAll()
        val songs = ThisIsPlaylists.interleave(lists).map { track ->
            // A video in an editorial list still plays as the song; the list shows the song's name.
            if (track.isVideo) track.copy(title = TextMatch.withoutVideoWords(track.title)) else track
        }
        songs to chosen.firstOrNull()?.artworkUrl
    }

    /** The user's songs that fit the mood, most listened first. */
    private suspend fun userSongs(mood: Mood): List<SongPlayStatsEntity> {
        val pool = runCatching { stats.mostPlayedSince(days = POOL_DAYS, limit = POOL_SIZE) }.getOrDefault(emptyList())
            .filter { it.playCount > 0 || it.totalPlayTimeMs >= MIN_LISTENED_MS }
            .filter { it.skipCount <= it.playCount + 1 }
            .filter { !it.videoId.isNullOrBlank() }
        if (pool.isEmpty()) return emptyList()
        val needsFacts = mood.decade != null || mood in TEMPO_MOODS
        val facts = if (needsFacts) {
            runCatching { metadata.factsFor(pool.map { it.title to it.artist }, FACTS_BUDGET_MS) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val tags = if (mood.decade == null) {
            runCatching { metadata.artistTagsFor(pool.map { primaryArtist(it.artist) }, TAGS_BUDGET_MS) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        return pool.filter { song ->
            fits(
                mood = mood,
                title = song.title,
                facts = facts[RadioRecommender.historyKey(song.title, song.artist)],
                tags = tags[primaryArtist(song.artist).lowercase()].orEmpty(),
            )
        }.take(ThisIsPlaylists.PERSONAL_LIMIT)
    }

    /** Alternates the user's songs and the catalog's, user first, without repeats; the catalog's version wins. */
    internal fun mix(catalog: List<YouTubeMusicTrack>, mine: List<SongPlayStatsEntity>): List<YouTubeMusicTrack> {
        val byKey = HashMap<String, YouTubeMusicTrack>()
        catalog.forEach { byKey.putIfAbsent(ThisIsPlaylists.songKey(it.title), it) }
        val mineTracks = mine.mapNotNull { song ->
            byKey[ThisIsPlaylists.songKey(song.title)]
                ?: song.videoId?.takeIf { it.isNotBlank() }?.let {
                    YouTubeMusicTrack(videoId = it, title = song.title, artist = song.artist, artworkUrl = song.artworkUrl)
                }
        }
        val out = ArrayList<YouTubeMusicTrack>()
        val seen = HashSet<String>()
        fun add(track: YouTubeMusicTrack) {
            if (out.size < MAX_TRACKS && seen.add(ThisIsPlaylists.songKey(track.title)) && seen.add("id:" + track.videoId)) out += track
        }
        val a = mineTracks.iterator()
        val b = catalog.iterator()
        while (out.size < MAX_TRACKS && (a.hasNext() || b.hasNext())) {
            if (a.hasNext()) add(a.next())
            if (b.hasNext()) add(b.next())
        }
        return out
    }

    companion object {
        const val PREFIX = "mood:"
        const val MAX_TRACKS = 60
        private const val KEEP_MS = 30 * 60 * 1000L
        private const val POOL_DAYS = 365
        private const val POOL_SIZE = 300
        private const val MIN_LISTENED_MS = 60_000L
        private const val FACTS_BUDGET_MS = 4_000L
        private const val TAGS_BUDGET_MS = 3_000L
        private const val SOURCE_TIMEOUT_MS = 10_000L
        private const val PLAYLISTS_PER_MOOD = 3
        private val TEMPO_MOODS = setOf(Mood.GYM, Mood.MOTIVACION, Mood.CHILL, Mood.FIESTA)
        private val PARTY_TAGS = listOf("dance", "reggaeton", "party", "house", "latin", "urbano", "dembow", "edm", "disco", "electro")
        private val UPBEAT_TAGS = listOf("dance", "upbeat", "happy", "rock", "pop punk", "edm", "electro", "workout", "hip hop", "rap", "reggaeton")

        fun idFor(mood: Mood): String = PREFIX + mood.id
        fun isMood(id: String): Boolean = id.startsWith(PREFIX)
        fun parse(id: String): Mood? = if (isMood(id)) Mood.byId(id.removePrefix(PREFIX)) else null

        private fun primaryArtist(artist: String): String =
            com.spotiaisexs.app.util.ArtistHelper.splitArtists(artist).firstOrNull()?.trim().orEmpty().ifBlank { artist }

        /**
         * YouTube Music's own playlists first (their ids start with RDCLAK, or
         * the author is YouTube Music), then the rest, never two of the same.
         */
        internal fun pickPlaylists(found: List<YouTubePlaylistSummary>): List<YouTubePlaylistSummary> =
            found.distinctBy { it.id }
                .sortedBy { if (it.id.startsWith("RDCLAK") || it.author?.contains("YouTube Music", ignoreCase = true) == true) 0 else 1 }
                .take(PLAYLISTS_PER_MOOD)

        /** Whether one of the user's songs fits [mood]. No evidence means no. */
        internal fun fits(mood: Mood, title: String, facts: TrackFacts?, tags: Set<String>): Boolean {
            mood.decade?.let { start -> return facts?.year?.let { it in start until start + 10 } == true }
            val bpm = facts?.bpm?.takeIf { it in 1 until RadioFilters.HALF_TIME_BPM }
            val tempo = RadioFilters.tempoEvidence(title, tags, bpm)
            val language = RadioFilters.titleLanguage(title)
            val spanish = language > 0 || RadioFilters.tagsSaySpanish(tags)
            val pop = tags.any { "pop" in it }
            return when (mood) {
                Mood.GYM -> tempo < 0 && (bpm == null || bpm >= 118)
                Mood.MOTIVACION -> tempo <= 0 && ((bpm != null && bpm >= 100) || tags.any { tag -> UPBEAT_TAGS.any { it in tag } })
                Mood.CHILL -> tempo > 0 || (bpm != null && bpm <= 95)
                Mood.FIESTA -> tempo <= 0 && tags.any { tag -> PARTY_TAGS.any { it in tag } } && (bpm == null || bpm >= 92)
                Mood.POP_ES -> spanish && (pop || (tags.isEmpty() && language > 0))
                Mood.POP_EN -> language < 0 && !RadioFilters.tagsSaySpanish(tags) && (pop || tags.isEmpty())
                else -> false
            }
        }
    }
}
