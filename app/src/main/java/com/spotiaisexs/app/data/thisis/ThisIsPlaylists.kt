package com.spotiaisexs.app.data.thisis

import com.spotiaisexs.app.data.local.db.SongPlayStatsEntity
import com.spotiaisexs.app.data.model.ArtistPageData
import com.spotiaisexs.app.data.music.InnerTubeMusicApi
import com.spotiaisexs.app.data.music.YouTubeMusicTrack
import com.spotiaisexs.app.data.music.YouTubePlaylistResult
import com.spotiaisexs.app.data.repository.ArtistRepository
import com.spotiaisexs.app.data.repository.SongPlayStatsRepository
import com.spotiaisexs.app.playback.PlayableTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** An "Esto es" playlist with the artist's name and the ids of the songs that come from the user's listening. */
data class ThisIsDetail(
    val playlist: YouTubePlaylistResult,
    val artistName: String,
    val mineVideoIds: Set<String>,
)

/**
 * "Esto es {artista}": an automatic playlist per artist, like Spotify's
 * "This Is". It mixes the artist's songs from YouTube Music with the songs of
 * that artist the user listened to most, alternating one of each so neither
 * side takes over. A song that is on both lists counts once, at its first place.
 *
 * The artist page alone sometimes gives only its five preview songs, so the
 * catalog side is filled, in this order, with the artist's top songs, a search
 * for their songs, the songs of their albums and singles (one album after
 * another, a song at a time) and their radio, keeping only songs where they
 * sing, until there are [MAX_TRACKS]. Only artists with fewer songs than
 * [MIN_TRACKS] in the whole catalog end up shorter.
 *
 * It opens in the same screen as the home's YouTube playlists, under an id
 * made by [idFor]; nothing is stored, it is rebuilt every time it is opened.
 */
@Singleton
class ThisIsPlaylists @Inject constructor(
    private val artistRepository: ArtistRepository,
    private val stats: SongPlayStatsRepository,
    private val innerTube: InnerTubeMusicApi,
) {
    /** The playlist alone, for callers that only play it. */
    suspend fun build(id: String): YouTubePlaylistResult = buildDetail(id).playlist

    /** The playlist plus what the screen needs to tell the user's songs from the hits. */
    suspend fun buildDetail(id: String): ThisIsDetail = coroutineScope {
        val (artist, browseId) = parse(id) ?: throw java.io.IOException("Esta playlist no existe.")
        val pageJob = async { runCatching { artistRepository.getArtistDetails(artist, browseId) }.getOrNull() }
        val mineJob = async { runCatching { stats.mostPlayedByArtist(artist, PERSONAL_LIMIT * 2) }.getOrDefault(emptyList()) }
        val page = pageJob.await()
        val name = page?.name?.takeIf { it.isNotBlank() } ?: artist
        val popular = page?.topSongs.orEmpty()
        val mine = mineJob.await()
            .filter { it.playCount > 0 || it.totalPlayTimeMs >= MIN_LISTENED_MS }
            // Songs the user keeps skipping are not "their" songs of this artist.
            .filter { it.skipCount <= it.playCount + 1 }
        val known = HashSet<String>()
        popular.forEach { known += songKey(it.title) }
        mine.take(PERSONAL_LIMIT).forEach { known += songKey(it.title) }
        val more = if (known.size < MAX_TRACKS) moreSongs(name, page, popular) else emptyList()
        val tracks = mix(popular, mine, more)
        if (tracks.isEmpty()) throw java.io.IOException("No hay canciones de $name para montar la playlist.")
        ThisIsDetail(
            playlist = YouTubePlaylistResult(
                id = id,
                title = "Esto es $name",
                author = if (mine.isEmpty()) "Sus canciones más escuchadas"
                else "Sus éxitos y lo que más le escuchas tú",
                artworkUrl = page?.artworkUrl ?: page?.fallbackArtworkUrl ?: tracks.first().artworkUrl,
                trackCount = tracks.size,
                tracks = tracks,
            ),
            artistName = name,
            mineVideoIds = mineIds(tracks, mine),
        )
    }

    /**
     * Which of the [tracks] the user listens to a lot. A song that is on both
     * lists counts as the user's even when the version kept is the hit's.
     */
    internal fun mineIds(tracks: List<YouTubeMusicTrack>, mine: List<SongPlayStatsEntity>): Set<String> {
        val mineKeys = mine.take(PERSONAL_LIMIT).mapTo(HashSet()) { songKey(it.title) }
        return tracks.filter { songKey(it.title) in mineKeys }.mapTo(HashSet()) { it.videoId }
    }

    /**
     * Alternates the user's favourites and the artist's songs ([popular] first,
     * then [more]), user first, without repeats. When a song of the user is also
     * in the catalog, the catalog's version is kept: it is the song itself
     * rather than the "(Official Video)" or "(Lyric Video)" the user may have
     * played.
     */
    internal fun mix(
        popular: List<PlayableTrack>,
        mine: List<SongPlayStatsEntity>,
        more: List<YouTubeMusicTrack> = emptyList(),
    ): List<YouTubeMusicTrack> {
        val popularTracks = popular.mapNotNull { it.asTrack() } + more
        val byTitle = HashMap<String, YouTubeMusicTrack>()
        popularTracks.forEach { byTitle.putIfAbsent(songKey(it.title), it) }
        val mineTracks = mine.mapNotNull { song ->
            byTitle[songKey(song.title)]?.let { return@mapNotNull it }
            // A song played from somewhere without a video id can still be matched to the artist page.
            val videoId = song.videoId?.takeIf { it.isNotBlank() } ?: byTitle[songKey(song.title)]?.videoId
            videoId?.let {
                YouTubeMusicTrack(
                    videoId = it,
                    title = song.title,
                    artist = song.artist,
                    artworkUrl = song.artworkUrl ?: byTitle[songKey(song.title)]?.artworkUrl,
                )
            }
        }.take(PERSONAL_LIMIT)

        val out = mutableListOf<YouTubeMusicTrack>()
        val seen = HashSet<String>()
        fun add(track: YouTubeMusicTrack) {
            if (out.size < MAX_TRACKS && seen.add(songKey(track.title)) && seen.add("id:" + track.videoId)) out += track
        }
        val a = mineTracks.iterator()
        val b = popularTracks.iterator()
        while (out.size < MAX_TRACKS && (a.hasNext() || b.hasNext())) {
            if (a.hasNext()) add(a.next())
            if (b.hasNext()) add(b.next())
        }
        return out
    }

    /**
     * More songs of [artist] beyond the artist page's top songs: a search for
     * their songs, the songs of their albums and singles and their radio, all
     * asked at once and each with its own time limit, so a slow or failed
     * source only means fewer songs, never an error.
     */
    private suspend fun moreSongs(
        artist: String,
        page: ArtistPageData?,
        popular: List<PlayableTrack>,
    ): List<YouTubeMusicTrack> = coroutineScope {
        val searchJob = async {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                runCatching { innerTube.searchSongs(artist, limit = 60, prefetchStreams = false) }.getOrNull()
            }.orEmpty()
        }
        val releases = (page?.albums.orEmpty() + page?.singles.orEmpty())
            .filter { it.browseId.isNotBlank() }
            .distinctBy { it.browseId }
            .take(MAX_RELEASES)
        val gate = Semaphore(4)
        val releaseJobs = releases.map { release ->
            async {
                gate.withPermit {
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                        runCatching { innerTube.fetchAlbumPage(release.browseId, release.title, artist) }.getOrNull()
                    }
                }?.tracks.orEmpty().mapNotNull { it.asTrack() }
            }
        }
        val radioSeed = popular.firstOrNull { !it.videoId.isNullOrBlank() }?.videoId
        val radioJob = async {
            radioSeed?.let { seed ->
                withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    runCatching { innerTube.fetchRelatedSongs(seed, limit = 60, prefetchStreams = false) }.getOrNull()
                }
            }.orEmpty()
        }
        val search = searchJob.await()
        val albums = interleave(releaseJobs.map { it.await() })
        val radio = radioJob.await()
        (search + albums + radio).filter { isBy(it.artist, artist) }
    }

    private fun PlayableTrack.asTrack(): YouTubeMusicTrack? = videoId?.takeIf { it.isNotBlank() }?.let {
        YouTubeMusicTrack(
            videoId = it,
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            durationSeconds = durationMs?.takeIf { ms -> ms > 0 }?.div(1_000L)?.toInt(),
        )
    }

    companion object {
        const val PREFIX = "thisis:"
        private const val SEPARATOR = "|"
        /** Every "Esto es" aims for this many songs… */
        const val MAX_TRACKS = 60
        /** …and only an artist with fewer songs than this in the whole catalog ends up shorter. */
        const val MIN_TRACKS = 50
        const val PERSONAL_LIMIT = 20
        private const val MIN_LISTENED_MS = 60_000L
        private const val MAX_RELEASES = 12
        private const val SOURCE_TIMEOUT_MS = 10_000L

        /** One song of each album in turn, so the playlist does not play a whole album in a row. */
        internal fun <T> interleave(lists: List<List<T>>): List<T> {
            val out = ArrayList<T>()
            val longest = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let(out::add) }
            return out
        }

        private fun normalized(name: String): String =
            java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replace(Regex("""\p{Mn}+"""), "")
                .lowercase()
                .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
                .trim()

        /**
         * Whether [artist] sings in a song credited to [credits] ("Bad Bunny",
         * "Bad Bunny & Jhayco", "Rosalía, Bad Bunny"…). Compares whole names,
         * so "Eme" is not taken for "Emeli Sandé".
         */
        internal fun isBy(credits: String?, artist: String): Boolean {
            val wanted = normalized(artist)
            if (wanted.isBlank() || credits.isNullOrBlank()) return false
            if (normalized(credits) == wanted) return true
            return com.spotiaisexs.app.util.ArtistHelper.splitArtists(credits).any { normalized(it) == wanted }
        }

        private val VERSION_NOISE = Regex("""\(.*?\)|\[.*?]|\s-\s.*$|\bfeat\.?.*$|\bft\.?.*$""", RegexOption.IGNORE_CASE)

        /** Same song in different versions ("(Remix)", "- Live", "feat. …") counts once. */
        internal fun songKey(title: String): String =
            title.lowercase().replace(VERSION_NOISE, " ").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

        fun idFor(artist: String, browseId: String? = null): String =
            PREFIX + artist.trim() + (browseId?.takeIf { it.isNotBlank() }?.let { SEPARATOR + it } ?: "")

        fun isThisIs(id: String): Boolean = id.startsWith(PREFIX)

        fun parse(id: String): Pair<String, String?>? {
            if (!isThisIs(id)) return null
            val body = id.removePrefix(PREFIX)
            val artist = body.substringBefore(SEPARATOR).trim()
            val browseId = body.substringAfter(SEPARATOR, "").trim().takeIf { it.isNotBlank() }
            return artist.takeIf { it.isNotBlank() }?.let { it to browseId }
        }
    }
}
