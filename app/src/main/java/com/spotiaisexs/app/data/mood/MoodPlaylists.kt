package com.spotiaisexs.app.data.mood

import com.spotiaisexs.app.data.generate.youtubeVideoIdOrNull
import com.spotiaisexs.app.data.local.db.SongPlayStatsEntity
import com.spotiaisexs.app.data.music.InnerTubeMusicApi
import com.spotiaisexs.app.data.music.TextMatch
import com.spotiaisexs.app.data.music.YouTubeMusicTrack
import com.spotiaisexs.app.data.music.YouTubePlaylistResult
import com.spotiaisexs.app.data.playlist.PlaylistRepository
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

/** A mood or kind of music with its own automatic playlist, made from the user's taste. */
enum class Mood(
    val id: String,
    val title: String,
    val subtitle: String,
    /** For the decades: the first year. */
    val decade: Int? = null,
) {
    GYM("gym", "Gym", "Lo tuyo con más ritmo"),
    MOTIVACION("motivacion", "Motivación", "Lo tuyo para venirte arriba"),
    CHILL("chill", "Chill", "Lo tuyo más tranquilo"),
    FIESTA("fiesta", "Fiesta", "Lo tuyo para la previa"),
    POP_ES("pop-es", "Pop español", "Tu pop cantado en español"),
    POP_EN("pop-en", "Pop inglés", "Tu pop cantado en inglés"),
    ANOS_70("70s", "Años 70", "Lo tuyo de los 70", decade = 1970),
    ANOS_80("80s", "Años 80", "Lo tuyo de los 80", decade = 1980),
    ANOS_90("90s", "Años 90", "Lo tuyo de los 90", decade = 1990),
    ANOS_2000("2000s", "Años 2000", "Lo tuyo de los 2000", decade = 2000),
    ;

    /** Moods that look at what the lyrics say (language, partying, pushing on). */
    val readsLyrics: Boolean get() = this in setOf(POP_ES, POP_EN, FIESTA, MOTIVACION)

    companion object {
        fun byId(id: String): Mood? = entries.firstOrNull { it.id == id }
    }
}

/** A mood playlist and which of its songs are the user's own (the rest are new songs like them). */
data class MoodDetail(
    val mood: Mood,
    val playlist: YouTubePlaylistResult,
    val mineVideoIds: Set<String>,
)

/**
 * Mood playlists ("Gym", "Chill", "Pop español", "Años 80"…) made from the
 * user's taste, not from anyone's editorial lists:
 *
 *  1. Their songs: the most listened of the last two years and everything in
 *     their library (playlists and Me gusta), without the ones they skip.
 *  2. Each song is checked against the mood with what can be known about it:
 *     year and tempo (Deezer), the artist's style (Last.fm tags, with the
 *     user's key), and the lyrics (LRCLIB): the language they are sung in and
 *     whether they keep talking about partying or about pushing on. A song
 *     without evidence for the mood is left out rather than guessed in.
 *  3. New songs like theirs: YouTube Music's radio of their songs that fit,
 *     checked against the mood the same way.
 *
 * Two of theirs, then one new, up to [MAX_TRACKS]. Kept for a while so
 * opening it again is instant; what was learned about each song is saved.
 */
@Singleton
class MoodPlaylists @Inject constructor(
    private val stats: SongPlayStatsRepository,
    private val playlists: PlaylistRepository,
    private val innerTube: InnerTubeMusicApi,
    private val metadata: RadioMetadata,
    private val lyrics: LyricSignals,
) {
    private val built = ConcurrentHashMap<Mood, Pair<Long, MoodDetail>>()

    private val warmedUp = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Learns, in the background and once per app start, what the mood
     * playlists need about the user's songs (year, tempo, style, lyrics). It
     * is all saved, so after the first time the playlists open at once and
     * with every song that fits, not only the ones learned while opening.
     */
    suspend fun warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        val yours = runCatching { yourSongs() }.getOrDefault(emptyList())
        if (yours.isEmpty()) return
        val pairs = yours.map { it.title to it.artist }
        runCatching { metadata.factsFor(pairs, WARM_UP_BUDGET_MS) }
        runCatching { metadata.artistTagsFor(yours.map { primaryArtist(it.artist) }, WARM_UP_BUDGET_MS) }
        runCatching { lyrics.signalsFor(pairs.take(WARM_UP_LYRICS), WARM_UP_BUDGET_MS) }
    }

    /** The last version of each mood built, for the web. */
    fun builtSoFar(): List<MoodDetail> = built.values.map { it.second }

    suspend fun build(id: String, maxAgeMs: Long = KEEP_MS): MoodDetail {
        val mood = parse(id) ?: throw java.io.IOException("Esta playlist no existe.")
        built[mood]?.let { (at, detail) -> if (System.currentTimeMillis() - at < maxAgeMs) return detail }
        val yours = yourSongs()
        if (yours.isEmpty()) throw java.io.IOException("Escucha algo de música primero: esta playlist se monta con tus canciones.")
        val fitting = fitting(mood, yours, YOURS_BUDGET)
        val skipped = skippedKeys()
        val known = yours.mapTo(HashSet()) { ThisIsPlaylists.songKey(it.title) }
        val newOnes = fitting(mood, likeYours(fitting, known, skipped), NEW_BUDGET)
        val tracks = asSongs(interleave(fitting, newOnes))
        if (tracks.isEmpty()) throw java.io.IOException(emptyMessage(mood))
        val yourKeys = fitting.mapTo(HashSet()) { ThisIsPlaylists.songKey(it.title) }
        val detail = MoodDetail(
            mood = mood,
            playlist = YouTubePlaylistResult(
                id = idFor(mood),
                title = mood.title,
                author = mood.subtitle,
                artworkUrl = tracks.first().artworkUrl,
                trackCount = tracks.size,
                tracks = tracks,
            ),
            mineVideoIds = tracks.filter { ThisIsPlaylists.songKey(it.title) in yourKeys }.mapTo(HashSet()) { it.videoId },
        )
        built[mood] = System.currentTimeMillis() to detail
        return detail
    }

    /**
     * Songs the user played as a video ("… (Official Video)") are swapped for
     * the song itself, with the song's name. If that takes too long the list
     * keeps them as they are: they still play as the song when their turn comes.
     */
    private suspend fun asSongs(tracks: List<YouTubeMusicTrack>): List<YouTubeMusicTrack> = coroutineScope {
        withTimeoutOrNull(SONG_VERSION_BUDGET_MS) {
            tracks.map { track ->
                async {
                    if (!TextMatch.looksLikeVideoTitle(track.title)) return@async track
                    val song = runCatching { innerTube.songVersionOf(track.videoId, track.title, track.artist) }.getOrNull()
                    track.copy(videoId = song ?: track.videoId, title = TextMatch.withoutVideoWords(track.title))
                }
            }.awaitAll()
        } ?: tracks
    }

    /** The user's songs, most listened first, then the rest of their library. */
    private suspend fun yourSongs(): List<YouTubeMusicTrack> {
        val played = runCatching { stats.mostPlayedSince(days = POOL_DAYS, limit = POOL_SIZE) }.getOrDefault(emptyList())
            .filter { it.playCount > 0 || it.totalPlayTimeMs >= MIN_LISTENED_MS }
            .filter { it.skipCount <= it.playCount + 1 }
            .mapNotNull { it.asTrack() }
        val library = runCatching { playlists.getAll() }.getOrDefault(emptyList())
            .flatMap { it.tracks }
            .mapNotNull { track ->
                track.youtubeVideoIdOrNull()?.let {
                    YouTubeMusicTrack(videoId = it, title = track.name, artist = track.artist, album = track.album, artworkUrl = track.artworkUrl)
                }
            }
        return distinctSongs(played + library)
    }

    private suspend fun skippedKeys(): Set<String> =
        runCatching { stats.mostPlayedSince(days = POOL_DAYS, limit = POOL_SIZE) }.getOrDefault(emptyList())
            .filter { it.skipCount > it.playCount + 1 }
            .mapTo(HashSet()) { ThisIsPlaylists.songKey(it.title) }

    /**
     * New songs like the user's: YouTube Music's radio of a few of their songs
     * that fit (one song of each radio in turn), without songs they already
     * have or keep skipping.
     */
    private suspend fun likeYours(seeds: List<YouTubeMusicTrack>, known: Set<String>, skipped: Set<String>): List<YouTubeMusicTrack> =
        coroutineScope {
            val radios = seeds.take(SEEDS).map { seed ->
                async {
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                        runCatching { innerTube.fetchRelatedSongs(seed.videoId, limit = 40, prefetchStreams = false) }.getOrNull()
                    }.orEmpty()
                }
            }.awaitAll()
            distinctSongs(ThisIsPlaylists.interleave(radios))
                .filter { ThisIsPlaylists.songKey(it.title) !in known && ThisIsPlaylists.songKey(it.title) !in skipped }
                .take(MAX_NEW_CANDIDATES)
        }

    /** The [songs] that fit [mood], in their order, after learning what is needed about them. */
    private suspend fun fitting(mood: Mood, songs: List<YouTubeMusicTrack>, budgetMs: Long): List<YouTubeMusicTrack> {
        if (songs.isEmpty()) return emptyList()
        val pairs = songs.map { it.title to it.artist }
        return coroutineScope {
            val factsJob = async {
                if (mood.decade != null || mood in TEMPO_MOODS) {
                    runCatching { metadata.factsFor(pairs, budgetMs) }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
            }
            val tagsJob = async {
                if (mood.decade == null) {
                    runCatching { metadata.artistTagsFor(songs.map { primaryArtist(it.artist) }, budgetMs) }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
            }
            val lyricsJob = async {
                // Lyrics take longest: only for the first songs, the rest are learned on later builds.
                if (mood.readsLyrics) {
                    runCatching { lyrics.signalsFor(pairs.take(LYRICS_PER_BUILD), budgetMs) }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
            }
            val facts = factsJob.await()
            val tags = tagsJob.await()
            val signals = lyricsJob.await()
            songs.filter { song ->
                val key = RadioRecommender.historyKey(song.title, song.artist)
                fits(
                    mood = mood,
                    title = song.title,
                    facts = facts[key] ?: metadata.cachedFacts(song.title, song.artist),
                    tags = tags[primaryArtist(song.artist).lowercase()].orEmpty(),
                    lyrics = signals[key] ?: lyrics.cached(song.title, song.artist),
                )
            }
        }
    }

    companion object {
        const val PREFIX = "mood:"
        const val MAX_TRACKS = 60
        const val KEEP_MS = 30 * 60 * 1000L
        private const val POOL_DAYS = 730
        private const val POOL_SIZE = 500
        private const val MIN_LISTENED_MS = 60_000L
        private const val YOURS_BUDGET = 7_000L
        private const val NEW_BUDGET = 5_000L
        private const val SOURCE_TIMEOUT_MS = 10_000L
        private const val SEEDS = 6
        private const val SONG_VERSION_BUDGET_MS = 5_000L
        private const val MAX_NEW_CANDIDATES = 150
        private const val LYRICS_PER_BUILD = 150
        private const val WARM_UP_BUDGET_MS = 90_000L
        private const val WARM_UP_LYRICS = 400
        private val TEMPO_MOODS = setOf(Mood.GYM, Mood.MOTIVACION, Mood.CHILL, Mood.FIESTA)
        private val PARTY_TAGS = listOf("dance", "reggaeton", "party", "house", "latin", "urbano", "dembow", "edm", "disco", "electro")
        private val UPBEAT_TAGS = listOf("dance", "upbeat", "happy", "rock", "pop punk", "edm", "electro", "workout", "hip hop", "rap", "reggaeton")
        private val URBAN_TAGS = listOf("reggaeton", "trap", "urbano", "dembow", "hip hop", "rap", "drill")

        fun idFor(mood: Mood): String = PREFIX + mood.id
        fun isMood(id: String): Boolean = id.startsWith(PREFIX)
        fun parse(id: String): Mood? = if (isMood(id)) Mood.byId(id.removePrefix(PREFIX)) else null

        private fun SongPlayStatsEntity.asTrack(): YouTubeMusicTrack? = videoId?.takeIf { it.isNotBlank() }?.let {
            YouTubeMusicTrack(videoId = it, title = title, artist = artist, artworkUrl = artworkUrl)
        }

        private fun primaryArtist(artist: String): String =
            com.spotiaisexs.app.util.ArtistHelper.splitArtists(artist).firstOrNull()?.trim().orEmpty().ifBlank { artist }

        private fun distinctSongs(songs: List<YouTubeMusicTrack>): List<YouTubeMusicTrack> {
            val seen = HashSet<String>()
            return songs.filter { seen.add(ThisIsPlaylists.songKey(it.title) + "|" + primaryArtist(it.artist).lowercase()) && seen.add("id:" + it.videoId) }
        }

        /** Two of the user's songs, then one new, without repeats, up to [MAX_TRACKS]. */
        internal fun interleave(yours: List<YouTubeMusicTrack>, newOnes: List<YouTubeMusicTrack>): List<YouTubeMusicTrack> {
            val out = ArrayList<YouTubeMusicTrack>()
            val seen = HashSet<String>()
            fun add(track: YouTubeMusicTrack) {
                if (out.size < MAX_TRACKS && seen.add(ThisIsPlaylists.songKey(track.title)) && seen.add("id:" + track.videoId)) out += track
            }
            val a = yours.iterator()
            val b = newOnes.iterator()
            while (out.size < MAX_TRACKS && (a.hasNext() || b.hasNext())) {
                if (a.hasNext()) add(a.next())
                if (a.hasNext()) add(a.next())
                if (b.hasNext()) add(b.next())
            }
            return out
        }

        internal fun emptyMessage(mood: Mood): String = when {
            mood.decade != null -> "Aún no hay canciones tuyas de ${mood.title.lowercase()}. Cuando escuches alguna, la playlist se montará a partir de ella."
            else -> "Aún no hay suficientes canciones tuyas que encajen con ${mood.title}. Vuelve a intentarlo en un rato: cada vez la app sabe más de tus canciones."
        }

        /** Whether a song fits [mood]. No evidence means no. */
        internal fun fits(mood: Mood, title: String, facts: TrackFacts?, tags: Set<String>, lyrics: LyricSignal? = null): Boolean {
            mood.decade?.let { start -> return facts?.year?.let { it in start until start + 10 } == true }
            if (lyrics?.instrumental == true && mood.readsLyrics && mood != Mood.FIESTA) return false
            val bpm = facts?.bpm?.takeIf { it in 1 until RadioFilters.HALF_TIME_BPM }
            val tempo = RadioFilters.tempoEvidence(title, tags, bpm)
            // The lyrics say best what language a song is in; the title and the artist's tags only when they can't.
            val sung = lyrics?.language?.takeIf { it != 0 }
            val spanishTags = RadioFilters.tagsSaySpanish(tags)
            val titleLanguage = RadioFilters.titleLanguage(title)
            val spanish = sung?.let { it > 0 } ?: (titleLanguage > 0 || spanishTags)
            val english = sung?.let { it < 0 } ?: (titleLanguage < 0 && !spanishTags)
            val pop = tags.any { "pop" in it || "cantautor" in it || "singer-songwriter" in it }
            val urban = tags.any { tag -> URBAN_TAGS.any { it in tag } }
            return when (mood) {
                Mood.GYM -> tempo < 0 && (bpm == null || bpm >= 118)
                Mood.MOTIVACION -> tempo <= 0 && (
                    lyrics?.motivation == true ||
                        (bpm != null && bpm >= 110) ||
                        tags.any { tag -> UPBEAT_TAGS.any { it in tag } }
                    )
                Mood.CHILL -> tempo > 0 || (bpm != null && bpm <= 95)
                Mood.FIESTA -> tempo <= 0 && (bpm == null || bpm >= 92) &&
                    (lyrics?.party == true || tags.any { tag -> PARTY_TAGS.any { it in tag } })
                Mood.POP_ES -> spanish && (pop || (tags.isEmpty() && sung != null)) && !(urban && !pop)
                Mood.POP_EN -> english && (pop || (tags.isEmpty() && sung != null)) && !(urban && !pop)
                else -> false
            }
        }
    }
}
