package com.spotiaisexs.app.data.mood

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 *     year, tempo and genre (Deezer, no key needed), the artist's usual genre
 *     (from all their songs the app knows), the artist's style (Last.fm tags,
 *     with the user's key), and the lyrics (LRCLIB): the language they are
 *     sung in and whether they talk about partying, about pushing on, or like
 *     reggaeton and trap do. A song without evidence for the mood is left out
 *     rather than guessed in.
 *  3. New songs like theirs: YouTube Music's radio of their songs that fit,
 *     checked against the mood the same way, until the list is full.
 *
 * Learning all that takes minutes the first time, so it is done in the
 * background while the app is open ([warmUp]), every playlist is built then
 * with time to spare and saved: opening one is instant and it comes full.
 */
@Singleton
class MoodPlaylists @Inject constructor(
    @ApplicationContext context: Context,
    private val stats: SongPlayStatsRepository,
    private val playlists: PlaylistRepository,
    private val innerTube: InnerTubeMusicApi,
    private val metadata: RadioMetadata,
    private val lyrics: LyricSignals,
) {
    private val prefs = context.applicationContext.getSharedPreferences("mood_playlists", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val built = ConcurrentHashMap<Mood, SavedMood>()
    private val locks = Mood.entries.associateWith { Mutex() }
    private val warmedUp = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Once per app start, in the background and for as long as the app lives:
     * learns what the moods need about the user's songs (year, tempo, genre,
     * style, lyrics), builds every mood playlist with time to spare and saves
     * them; again every few hours, so they follow what the user listens to.
     */
    fun warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                runCatching { learnYourSongs() }
                for (mood in Mood.entries) {
                    runCatching { build(idFor(mood), maxAgeMs = REFRESH_MS, deep = true) }
                }
                delay(REFRESH_MS)
            }
        }
    }

    private suspend fun learnYourSongs() {
        val yours = yourSongs()
        if (yours.isEmpty()) return
        val pairs = yours.map { it.title to it.artist }
        coroutineScope {
            val facts = async { runCatching { metadata.factsFor(pairs, DEEP_BUDGET_MS) } }
            val tags = async { runCatching { metadata.artistTagsFor(yours.map { primaryArtist(it.artist) }, DEEP_BUDGET_MS) } }
            val words = async { runCatching { lyrics.signalsFor(pairs, DEEP_BUDGET_MS) } }
            facts.await(); tags.await(); words.await()
        }
    }

    /**
     * The playlist for [id]. A saved one younger than [maxAgeMs] is returned
     * at once (a quick one only until a [deep] one replaces it); otherwise it
     * is built now: quickly when the user is waiting, thoroughly when [deep].
     */
    suspend fun build(id: String, maxAgeMs: Long = REFRESH_MS, deep: Boolean = false): MoodDetail {
        val mood = parse(id) ?: throw java.io.IOException("Esta playlist no existe.")
        return locks.getValue(mood).withLock {
            val saved = built[mood] ?: loadSaved(mood)?.also { built[mood] = it }
            val fresh = saved != null && System.currentTimeMillis() - saved.builtAt < maxAgeMs && (saved.deep || !deep)
            if (fresh && saved != null && saved.tracks.isNotEmpty()) return@withLock saved.toDetail(mood)
            val made = runCatching { make(mood, deep) }
            made.getOrNull()?.let { detail ->
                val record = SavedMood.of(detail, deep)
                built[mood] = record
                runCatching { prefs.edit().putString(saveKey(mood), json.encodeToString(SavedMood.serializer(), record)).apply() }
                return@withLock detail
            }
            // A failed rebuild (no network…) keeps showing the last good one.
            saved?.takeIf { it.tracks.isNotEmpty() }?.let { return@withLock it.toDetail(mood) }
            throw made.exceptionOrNull() ?: java.io.IOException(emptyMessage(mood))
        }
    }

    private fun loadSaved(mood: Mood): SavedMood? = runCatching {
        prefs.getString(saveKey(mood), null)?.let { json.decodeFromString(SavedMood.serializer(), it) }
    }.getOrNull()

    private suspend fun make(mood: Mood, deep: Boolean): MoodDetail {
        val budget = if (deep) DEEP_BUDGET_MS else QUICK_BUDGET_MS
        val yours = yourSongs()
        if (yours.isEmpty()) throw java.io.IOException("Escucha algo de música primero: esta playlist se monta con tus canciones.")
        val fitting = fitting(mood, yours, budget, lyricsLimit = if (deep) Int.MAX_VALUE else QUICK_LYRICS, profileOf = yours)
        val skipped = skippedKeys()
        val known = yours.mapTo(HashSet()) { ThisIsPlaylists.songKey(it.title) }
        val candidates = newCandidates(mood, yours, fitting, known, skipped, deep)
        val newOnes = fitting(mood, candidates, budget, lyricsLimit = if (deep) Int.MAX_VALUE else QUICK_LYRICS, profileOf = yours + candidates)
        val tracks = asSongs(interleave(fitting, newOnes))
        if (tracks.isEmpty()) throw java.io.IOException(emptyMessage(mood))
        val yourKeys = fitting.mapTo(HashSet()) { ThisIsPlaylists.songKey(it.title) }
        return MoodDetail(
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
     * New songs like the user's: YouTube Music's radio of their songs that fit
     * (spread over the whole list, one song of each radio in turn), without
     * songs they already have or keep skipping. When few of theirs fit, the
     * radios start from their other songs too, and for a decade from their
     * songs closest in time and from that decade's songs in the language they
     * listen to most.
     */
    private suspend fun newCandidates(
        mood: Mood,
        yours: List<YouTubeMusicTrack>,
        fitting: List<YouTubeMusicTrack>,
        known: Set<String>,
        skipped: Set<String>,
        deep: Boolean,
    ): List<YouTubeMusicTrack> = coroutineScope {
        val seedCount = if (deep) DEEP_SEEDS else QUICK_SEEDS
        val extra = ArrayList<YouTubeMusicTrack>()
        val seeds = ArrayList(spread(fitting, seedCount))
        if (seeds.size < seedCount) {
            val decade = mood.decade
            if (decade != null) {
                val middle = decade + 5
                val closest = yours.mapNotNull { song -> metadata.cachedFacts(song.title, song.artist)?.year?.let { song to it } }
                    .filter { (song, year) -> kotlin.math.abs(year - middle) <= 15 && song !in seeds }
                    .sortedBy { (_, year) -> kotlin.math.abs(year - middle) }
                    .map { it.first }
                seeds += closest.take(seedCount - seeds.size)
                val queries = decadeQueries(decade, spanishListener(yours))
                val found = queries.map { query ->
                    async {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            runCatching { innerTube.searchSongs(query, limit = 20, prefetchStreams = false) }.getOrNull()
                        }.orEmpty()
                    }
                }.awaitAll()
                extra += ThisIsPlaylists.interleave(found)
                if (seeds.size < seedCount) seeds += extra.filter { !it.isVideo }.take(seedCount - seeds.size)
            } else {
                seeds += yours.filter { it !in seeds }.take(seedCount - seeds.size)
            }
        }
        val radios = seeds.map { seed ->
            async {
                withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    runCatching { innerTube.fetchRelatedSongs(seed.videoId, limit = 40, prefetchStreams = false) }.getOrNull()
                }.orEmpty()
            }
        }.awaitAll()
        distinctSongs(ThisIsPlaylists.interleave(listOf(extra) + radios))
            .filter { ThisIsPlaylists.songKey(it.title) !in known && ThisIsPlaylists.songKey(it.title) !in skipped }
            .take(if (deep) DEEP_CANDIDATES else QUICK_CANDIDATES)
    }

    /** Whether most of the user's songs whose lyrics are known are sung in Spanish. */
    private fun spanishListener(yours: List<YouTubeMusicTrack>): Boolean {
        val sung = yours.mapNotNull { lyrics.cached(it.title, it.artist)?.language?.takeIf { l -> l != 0 } }
        return sung.isEmpty() || sung.count { it > 0 } * 2 >= sung.size
    }

    /** The [songs] that fit [mood], in their order, after learning what is needed about them. */
    private suspend fun fitting(
        mood: Mood,
        songs: List<YouTubeMusicTrack>,
        budgetMs: Long,
        lyricsLimit: Int,
        profileOf: List<YouTubeMusicTrack>,
    ): List<YouTubeMusicTrack> {
        if (songs.isEmpty()) return emptyList()
        val pairs = songs.map { it.title to it.artist }
        return coroutineScope {
            val factsJob = async { runCatching { metadata.factsFor(pairs, budgetMs) }.getOrDefault(emptyMap()) }
            val tagsJob = async {
                if (mood.decade == null) {
                    runCatching { metadata.artistTagsFor(songs.map { primaryArtist(it.artist) }, budgetMs) }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
            }
            val lyricsJob = async {
                if (mood.decade == null) {
                    runCatching { lyrics.signalsFor(pairs.take(lyricsLimit), budgetMs) }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
            }
            val facts = factsJob.await()
            val tags = tagsJob.await()
            val signals = lyricsJob.await()
            val profiles = artistGenres(profileOf.map { song ->
                primaryArtist(song.artist).lowercase() to metadata.cachedFacts(song.title, song.artist)?.genres.orEmpty()
            })
            songs.filter { song ->
                val key = RadioRecommender.historyKey(song.title, song.artist)
                val artist = primaryArtist(song.artist)
                fits(
                    mood = mood,
                    title = song.title,
                    facts = facts[key] ?: metadata.cachedFacts(song.title, song.artist),
                    tags = tags[artist.lowercase()].orEmpty(),
                    lyrics = signals[key] ?: lyrics.cached(song.title, song.artist),
                    artistGenres = profiles[artist.lowercase()].orEmpty(),
                    artist = song.artist,
                )
            }
        }
    }

    /** A saved mood playlist: what was built, when, and whether with time to spare. */
    @Serializable
    internal data class SavedMood(val builtAt: Long, val deep: Boolean, val tracks: List<SavedTrack>) {
        fun toDetail(mood: Mood): MoodDetail {
            val list = tracks.map { YouTubeMusicTrack(it.videoId, it.title, it.artist, it.album, it.artworkUrl, it.durationSeconds) }
            return MoodDetail(
                mood = mood,
                playlist = YouTubePlaylistResult(
                    id = idFor(mood),
                    title = mood.title,
                    author = mood.subtitle,
                    artworkUrl = list.firstOrNull()?.artworkUrl,
                    trackCount = list.size,
                    tracks = list,
                ),
                mineVideoIds = tracks.filter { it.mine }.mapTo(HashSet()) { it.videoId },
            )
        }

        companion object {
            fun of(detail: MoodDetail, deep: Boolean) = SavedMood(
                builtAt = System.currentTimeMillis(),
                deep = deep,
                tracks = detail.playlist.tracks.map {
                    SavedTrack(it.videoId, it.title, it.artist, it.album, it.artworkUrl, it.durationSeconds, it.videoId in detail.mineVideoIds)
                },
            )
        }
    }

    @Serializable
    internal data class SavedTrack(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val artworkUrl: String? = null,
        val durationSeconds: Int? = null,
        val mine: Boolean = false,
    )

    companion object {
        const val PREFIX = "mood:"
        const val MAX_TRACKS = 60
        /** How long a built playlist is kept before it is built again. */
        const val REFRESH_MS = 6 * 60 * 60 * 1000L
        /** Bumped when the rules change, so playlists made with the old ones are not shown. */
        private const val RULES_VERSION = 3
        private const val POOL_DAYS = 730
        private const val POOL_SIZE = 500
        private const val MIN_LISTENED_MS = 60_000L
        private const val QUICK_BUDGET_MS = 8_000L
        private const val DEEP_BUDGET_MS = 4 * 60_000L
        private const val QUICK_LYRICS = 150
        private const val SOURCE_TIMEOUT_MS = 10_000L
        private const val QUICK_SEEDS = 6
        private const val DEEP_SEEDS = 12
        private const val QUICK_CANDIDATES = 150
        private const val DEEP_CANDIDATES = 320
        private const val SONG_VERSION_BUDGET_MS = 5_000L

        private val PARTY_GENRES = listOf("reggaeton", "dance", "electro", "house", "dembow", "edm", "disco", "party", "techno")
        private val ENERGY_GENRES = listOf(
            "reggaeton", "rap", "hip hop", "hip-hop", "trap", "drill", "dembow", "dance", "electro", "edm", "house",
            "techno", "metal", "hard rock", "punk", "phonk", "hardstyle", "drum and bass", "dnb", "trance", "workout",
        )
        private val URBAN_GENRES = listOf("reggaeton", "rap", "hip hop", "hip-hop", "trap", "drill", "dembow", "urbano", "urbana", "latin urban")
        private val POP_GENRES = listOf("pop", "alternative", "alternativ", "indie", "rock", "cantautor", "singer-songwriter", "singer & songwriter", "folk")
        private val CALM_GENRES = listOf("pop", "folk", "singer", "cantautor", "jazz", "soul", "r&b", "alternative", "indie", "acoustic", "classical", "bossa", "chill")
        /** Styles that move but are not for the gym: rumba, songwriters, flamenco… */
        private val NOT_GYM = listOf("rumba", "cantautor", "singer-songwriter", "flamenco", "folk", "acoustic", "ballad", "balada", "bolero", "copla")
        private val UPBEAT_TAGS = listOf("upbeat", "happy", "workout", "pop punk", "power pop", "feel good", "anthem")

        /**
         * Artists that are reggaeton or trap whatever a catalogue files them
         * under (Deezer files a lot of it as "Latin" or "Pop"). Lowercased.
         */
        private val URBAN_ARTISTS = setOf(
            "bad bunny", "karol g", "anuel aa", "anuel", "jc reyes", "conep", "eladio carrión", "eladio carrion", "quevedo",
            "myke towers", "feid", "rauw alejandro", "ozuna", "daddy yankee", "j balvin", "wisin & yandel", "wisin y yandel",
            "wisin", "yandel", "don omar", "nicky jam", "maluma", "farruko", "arcángel", "arcangel", "de la ghetto", "sech",
            "jhayco", "jhay cortez", "mora", "young miko", "tainy", "duki", "trueno", "morad", "beny jr", "lunay", "saiko",
            "omar courtz", "dei v", "ryan castro", "blessd", "bizarrap", "bad gyal", "cruz cafuné", "natos y waor", "recycled j",
            "kidd keo", "yung beef", "delaossa", "kaydy cain", "dellafuente", "juseph", "clarent", "lenny tavárez",
            "lenny tavarez", "justin quiles", "zion & lennox", "plan b", "tito el bambino", "héctor el father", "jowell & randy",
            "anitta", "rvfv", "hades66", "west srk", "jere klein", "paulo londra", "khea", "nicki nicole", "tiago pzk",
            "maria becerra", "emilia", "lit killah", "ñengo flow", "bryant myers", "brray", "dalex", "chencho corleone",
            "jay wheeler", "gonzy", "kris r.", "eladio", "c.r.o", "ysy a", "cris mj", "pablo chill-e", "pailita", "milo j",
            "el alfa", "rochy rd", "tokischa", "yovngchimi", "luar la l", "mora", "jhayco", "lyanno", "rafa pabön",
        )

        fun idFor(mood: Mood): String = PREFIX + mood.id
        fun isMood(id: String): Boolean = id.startsWith(PREFIX)
        fun parse(id: String): Mood? = if (isMood(id)) Mood.byId(id.removePrefix(PREFIX)) else null

        private fun saveKey(mood: Mood) = "v${RULES_VERSION}_${mood.id}"

        private fun SongPlayStatsEntity.asTrack(): YouTubeMusicTrack? = videoId?.takeIf { it.isNotBlank() }?.let {
            YouTubeMusicTrack(videoId = it, title = title, artist = artist, artworkUrl = artworkUrl)
        }

        private fun primaryArtist(artist: String): String =
            com.spotiaisexs.app.util.ArtistHelper.splitArtists(artist).firstOrNull()?.trim().orEmpty().ifBlank { artist }

        private fun distinctSongs(songs: List<YouTubeMusicTrack>): List<YouTubeMusicTrack> {
            val seen = HashSet<String>()
            return songs.filter { seen.add(ThisIsPlaylists.songKey(it.title) + "|" + primaryArtist(it.artist).lowercase()) && seen.add("id:" + it.videoId) }
        }

        /** Up to [count] songs taken evenly from the whole list, so the radios don't all start from the same corner. */
        internal fun <T> spread(list: List<T>, count: Int): List<T> {
            if (list.size <= count) return list
            val step = list.size.toDouble() / count
            return (0 until count).map { list[(it * step).toInt()] }
        }

        /** Searches that find songs of a decade, in the language the user listens to most. */
        internal fun decadeQueries(decade: Int, spanish: Boolean): List<String> {
            val short = decade % 100
            return if (spanish) {
                listOf("éxitos españoles años $short", "pop español años $short", "canciones en español de los $short")
            } else {
                listOf("${short}s hits", "${short}s pop", "best songs of the ${short}s")
            }
        }

        /**
         * The genres an artist usually has: those on at least 40% of their songs
         * whose genres are known. [songs] are (artist lowercased, song genres).
         */
        internal fun artistGenres(songs: List<Pair<String, List<String>>>): Map<String, Set<String>> =
            songs.filter { it.second.isNotEmpty() }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, lists) ->
                    val counts = lists.flatMap { it.distinct() }.groupingBy { it }.eachCount()
                    counts.filter { (_, n) -> n * 10 >= lists.size * 4 }.keys
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
            mood.decade != null -> "Aún no hay canciones de ${mood.title.lowercase()} que encajen con lo que escuchas. Vuelve en un rato: la app sigue aprendiendo de tus canciones."
            else -> "Aún no hay suficientes canciones tuyas que encajen con ${mood.title}. Vuelve a intentarlo en un rato: cada vez la app sabe más de tus canciones."
        }

        private fun Collection<String>.mentions(words: List<String>): Boolean = any { item -> words.any { it in item } }

        /**
         * Whether a song fits [mood]. No evidence means no.
         *
         * [facts] carry the year, tempo and genres of the song's album (Deezer);
         * [artistGenres] the genres the artist usually has; [tags] the artist's
         * Last.fm tags, most used first; [lyrics] what the lyrics say.
         */
        internal fun fits(
            mood: Mood,
            title: String,
            facts: TrackFacts?,
            tags: Set<String>,
            lyrics: LyricSignal? = null,
            artistGenres: Set<String> = emptySet(),
            artist: String = "",
        ): Boolean {
            mood.decade?.let { start -> return facts?.year?.let { it in start until start + 10 } == true }
            if (lyrics?.instrumental == true && mood.readsLyrics && mood != Mood.FIESTA) return false
            val bpm = facts?.bpm?.takeIf { it in 1 until RadioFilters.HALF_TIME_BPM }
            val genres = facts?.genres.orEmpty()
            val allGenres = genres + artistGenres
            val mainTags = tags.take(5)
            val slow = RadioFilters.tempoEvidence(title, tags, bpm) > 0

            // Reggaeton and trap, from any of the four sources.
            val urbanArtist = com.spotiaisexs.app.util.ArtistHelper.splitArtists(artist).ifEmpty { listOf(artist) }
                .any { it.trim().lowercase() in URBAN_ARTISTS }
            val urban = urbanArtist || allGenres.mentions(URBAN_GENRES) || mainTags.take(3).mentions(URBAN_GENRES) || lyrics?.urban == true
            val energetic = urban || allGenres.mentions(ENERGY_GENRES) || mainTags.mentions(ENERGY_GENRES)
            val partyStyle = allGenres.mentions(PARTY_GENRES) || mainTags.mentions(PARTY_GENRES) ||
                (urban && !allGenres.mentions(listOf("rap", "hip hop", "trap", "drill")))
            val notForGym = mainTags.mentions(NOT_GYM) || genres.mentions(NOT_GYM)

            // The lyrics say best what language a song is in; the title and the artist's tags only when they can't.
            val sung = lyrics?.language?.takeIf { it != 0 }
            val spanishTags = RadioFilters.tagsSaySpanish(tags)
            val titleLanguage = RadioFilters.titleLanguage(title)
            val spanish = sung?.let { it > 0 } ?: (titleLanguage > 0 || spanishTags)
            val english = sung?.let { it < 0 } ?: (titleLanguage < 0 && !spanishTags)
            val nothingKnown = genres.isEmpty() && artistGenres.isEmpty() && tags.isEmpty()
            val popStyle = genres.mentions(POP_GENRES) || artistGenres.mentions(POP_GENRES) || mainTags.mentions(POP_GENRES) ||
                (nothingKnown && sung != null)

            return when (mood) {
                // Only music made to push: reggaeton, trap, rap, electronic, metal, punk… never rumba or a songwriter.
                Mood.GYM -> energetic && !slow && !notForGym && (bpm == null || bpm >= 85 || urban)
                Mood.MOTIVACION -> !slow && (
                    energetic ||
                        lyrics?.motivation == true ||
                        mainTags.mentions(UPBEAT_TAGS) ||
                        (popStyle && bpm != null && bpm >= 118)
                    )
                Mood.CHILL -> !energetic && lyrics?.party != true && (
                    slow || (bpm != null && bpm <= 95 && (allGenres.mentions(CALM_GENRES) || mainTags.mentions(CALM_GENRES)))
                    )
                Mood.FIESTA -> !slow && (bpm == null || bpm >= 92) && (partyStyle || lyrics?.party == true)
                // Pop is pop: anything reggaeton or trap is out, even when a catalogue calls it pop.
                Mood.POP_ES -> spanish && popStyle && !urban
                Mood.POP_EN -> english && popStyle && !urban
                else -> false
            }
        }
    }
}
