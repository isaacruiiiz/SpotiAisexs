package com.spotiaisexs.app.data.thisis

import com.spotiaisexs.app.data.local.db.SongPlayStatsEntity
import com.spotiaisexs.app.data.music.YouTubeMusicTrack
import com.spotiaisexs.app.data.music.YouTubePlaylistResult
import com.spotiaisexs.app.data.repository.ArtistRepository
import com.spotiaisexs.app.data.repository.SongPlayStatsRepository
import com.spotiaisexs.app.playback.PlayableTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Esto es {artista}": an automatic playlist per artist, like Spotify's
 * "This Is". It mixes the artist's most popular songs (the artist page's top
 * songs on YouTube Music) with the songs of that artist the user listened to
 * most, alternating one of each so neither side takes over. A song that is on
 * both lists counts once, at its first place.
 *
 * It opens in the same screen as the home's YouTube playlists, under an id
 * made by [idFor]; nothing is stored, it is rebuilt every time it is opened.
 */
/** An "Esto es" playlist with the artist's name and the ids of the songs that come from the user's listening. */
data class ThisIsDetail(
    val playlist: YouTubePlaylistResult,
    val artistName: String,
    val mineVideoIds: Set<String>,
)

@Singleton
class ThisIsPlaylists @Inject constructor(
    private val artistRepository: ArtistRepository,
    private val stats: SongPlayStatsRepository,
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
        val tracks = mix(popular, mine)
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

    /** Alternates the user's favourites and the artist's hits, user first, without repeats. */
    internal fun mix(popular: List<PlayableTrack>, mine: List<SongPlayStatsEntity>): List<YouTubeMusicTrack> {
        val popularTracks = popular.mapNotNull { it.asTrack() }
        val byTitle = popularTracks.associateBy { songKey(it.title) }
        val mineTracks = mine.mapNotNull { song ->
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
        const val MAX_TRACKS = 40
        const val PERSONAL_LIMIT = 20
        private const val MIN_LISTENED_MS = 60_000L

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
