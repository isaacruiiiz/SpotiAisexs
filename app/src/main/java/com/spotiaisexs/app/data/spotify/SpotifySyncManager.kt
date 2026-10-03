package com.spotiaisexs.app.data.spotify

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.spotiaisexs.app.data.generate.GeneratedTrack
import com.spotiaisexs.app.data.generate.youtubeVideoIdOrNull
import com.spotiaisexs.app.data.local.readSafely
import com.spotiaisexs.app.data.local.recoverPreferences
import com.spotiaisexs.app.data.music.InnerTubeMusicApi
import com.spotiaisexs.app.data.playlist.ExternalPlaylistLink
import com.spotiaisexs.app.data.playlist.ExternalPlaylistSource
import com.spotiaisexs.app.data.playlist.PlaylistRepository
import com.spotiaisexs.app.data.playlist.SavedPlaylist
import com.spotiaisexs.app.data.playlist.SpotifyPlaylistImporter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "SpotifySync"

/** A Spotify playlist kept in sync with a local SpotiAisexs playlist. */
@Serializable
data class SpotifyLink(
    val spotifyId: String,
    val url: String,
    val localPlaylistId: Long,
    val title: String,
    val lastSyncAtMillis: Long = 0L,
    val lastTrackCount: Int = 0,
)

data class SpotifySyncReport(
    val title: String,
    val added: Int,
    val removed: Int,
    val unmatched: Int,
    val total: Int,
)

/**
 * Uses Spotify as the *library* while audio keeps coming from YouTube Music.
 *
 * Linked Spotify playlists are re-read periodically through the public embed
 * page (the same path [SpotifyPlaylistImporter] already uses, so no Spotify
 * account, Premium or API key is needed) and mirrored into local playlists:
 * new songs are matched to YouTube Music and appended in Spotify's order,
 * removed songs disappear. Songs that were already matched are reused, so a
 * sync only searches YouTube for what actually changed.
 *
 * The playlists must be public. To bring "Liked Songs" over, copy them into a
 * public playlist in Spotify (select all → Add to playlist) and link that one.
 *
 * Deleting the local playlist in SpotiAisexs unlinks it automatically.
 *
 * The synced tracks also feed the radio recommender ([tasteSeeds] and
 * [tasteArtists]) so endless playback follows the Spotify taste.
 */
@Singleton
class SpotifySyncManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val importer: SpotifyPlaylistImporter,
    private val innerTube: InnerTubeMusicApi,
    private val playlistRepository: PlaylistRepository,
    private val applicationScope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()
    private var heartbeat: Job? = null

    @Volatile private var cachedTaste: List<GeneratedTrack>? = null
    @Volatile private var cachedTasteAtMillis = 0L

    val links: Flow<List<SpotifyLink>> = dataStore.data
        .recoverPreferences(TAG)
        .map { prefs -> decode(prefs.readSafely(LINKS_KEY)) }

    /** Starts the background refresh loop. Safe to call more than once. */
    fun start() {
        if (heartbeat?.isActive == true) return
        heartbeat = applicationScope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { syncAll(force = false) }
                    .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Periodic sync failed", it) }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Imports a Spotify playlist and keeps it linked. If it is already linked,
     * this just forces a sync of that playlist.
     */
    suspend fun link(url: String): Pair<SavedPlaylist, SpotifySyncReport> = withContext(Dispatchers.IO) {
        val id = ExternalPlaylistLink.extractId(url, ExternalPlaylistSource.SPOTIFY)
            ?: throw IllegalArgumentException("Eso no parece un enlace de playlist de Spotify.")

        syncMutex.withLock {
            val existing = currentLinks().firstOrNull { it.spotifyId == id }
            val existingPlaylist = existing?.let { playlistRepository.getById(it.localPlaylistId) }
            if (existing != null && existingPlaylist != null) {
                val report = syncLinkLocked(existing) ?: SpotifySyncReport(existing.title, 0, 0, 0, existing.lastTrackCount)
                val refreshed = playlistRepository.getById(existing.localPlaylistId) ?: existingPlaylist
                return@withLock refreshed to report
            }

            val remote = importer.fetchPlaylist(url)
            require(remote.rows.isNotEmpty()) {
                "No se han encontrado canciones. Comprueba que la playlist sea pública."
            }
            val matched = matchRows(remote.rows.map { it.title to it.artist }, reuse = emptyList())
            val tracks = matched.filterNotNull()
            require(tracks.isNotEmpty()) { "No se ha podido encontrar ninguna canción en YouTube Music." }

            val saved = playlistRepository.save(
                title = remote.title,
                subtitle = "Spotify (sincronizada) \u2022 ${tracks.size} canciones",
                mode = "custom",
                tracks = tracks,
            )
            val link = SpotifyLink(
                spotifyId = id,
                url = url.trim(),
                localPlaylistId = saved.id,
                title = remote.title,
                lastSyncAtMillis = System.currentTimeMillis(),
                lastTrackCount = remote.rows.size,
            )
            writeLinks(currentLinks().filterNot { it.spotifyId == id } + link)
            invalidateTaste()
            saved to SpotifySyncReport(remote.title, tracks.size, 0, remote.rows.size - tracks.size, remote.rows.size)
        }
    }

    suspend fun unlink(spotifyId: String) = syncMutex.withLock {
        writeLinks(currentLinks().filterNot { it.spotifyId == spotifyId })
        invalidateTaste()
    }

    /**
     * Syncs every linked playlist. Without [force], playlists synced less than
     * [MIN_SYNC_AGE_MS] ago are skipped.
     */
    suspend fun syncAll(force: Boolean = true): List<SpotifySyncReport> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val now = System.currentTimeMillis()
            currentLinks()
                .filter { force || now - it.lastSyncAtMillis >= MIN_SYNC_AGE_MS }
                .mapNotNull { link ->
                    try {
                        syncLinkLocked(link)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Sync failed for ${link.title}", e)
                        null
                    }
                }
                .also { if (it.isNotEmpty()) invalidateTaste() }
        }
    }

    /** Random tracks from the synced Spotify library, used as extra radio seeds. */
    suspend fun tasteSeeds(limit: Int): List<GeneratedTrack> =
        tasteTracks().filter { it.youtubeVideoIdOrNull() != null }.shuffled().take(limit)

    /** Lowercased artists present in the synced Spotify library. */
    suspend fun tasteArtists(): Set<String> =
        tasteTracks().mapTo(mutableSetOf()) { it.artist.trim().lowercase() }

    // ── internals ──────────────────────────────────────────────────────────

    /** Caller must hold [syncMutex]. Returns null when the link went stale and was removed. */
    private suspend fun syncLinkLocked(link: SpotifyLink): SpotifySyncReport? {
        val local = playlistRepository.getById(link.localPlaylistId)
        if (local == null) {
            // The user deleted the local copy: stop syncing it.
            writeLinks(currentLinks().filterNot { it.spotifyId == link.spotifyId })
            return null
        }

        val remote = importer.fetchPlaylist(link.url)
        if (remote.rows.isEmpty()) {
            // Private now, or Spotify changed the page: never wipe the local copy because of that.
            Log.w(TAG, "\"${link.title}\" returned no rows; keeping local copy untouched")
            return null
        }

        val matched = matchRows(remote.rows.map { it.title to it.artist }, reuse = local.tracks)
        val newTracks = matched.filterNotNull()
        val oldKeys = local.tracks.mapTo(mutableSetOf()) { it.key }
        val newKeys = newTracks.mapTo(mutableSetOf()) { it.key }

        if (newTracks.isNotEmpty() && newTracks != local.tracks) {
            val updated = playlistRepository.replaceTracksForSync(local.id, newTracks, expectedTracks = local.tracks)
            if (updated == null) Log.i(TAG, "\"${link.title}\" was edited meanwhile; retrying next round")
        }

        writeLinks(
            currentLinks().map {
                if (it.spotifyId == link.spotifyId) {
                    it.copy(
                        title = remote.title,
                        lastSyncAtMillis = System.currentTimeMillis(),
                        lastTrackCount = remote.rows.size,
                    )
                } else {
                    it
                }
            },
        )
        return SpotifySyncReport(
            title = remote.title,
            added = (newKeys - oldKeys).size,
            removed = (oldKeys - newKeys).size,
            unmatched = matched.count { it == null },
            total = remote.rows.size,
        )
    }

    /**
     * Resolves (title, artist) rows to playable tracks, keeping Spotify's order.
     * Rows already present in [reuse] are not searched again.
     */
    private suspend fun matchRows(
        rows: List<Pair<String, String>>,
        reuse: List<GeneratedTrack>,
    ): List<GeneratedTrack?> = coroutineScope {
        val known = reuse.associateBy { it.key }
        val limiter = Semaphore(4)
        rows.map { (title, artist) ->
            async {
                val probe = GeneratedTrack(name = title, artist = artist)
                known[probe.key]?.let { return@async it }
                limiter.withPermit {
                    try {
                        innerTube.findBestMatchOrNull(title, artist, prefetchStreams = false)?.let { match ->
                            GeneratedTrack(
                                name = title,
                                artist = artist,
                                album = match.album,
                                artworkUrl = match.artworkUrl,
                                url = "https://music.youtube.com/watch?v=${match.videoId}",
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun tasteTracks(): List<GeneratedTrack> {
        val now = System.currentTimeMillis()
        cachedTaste?.takeIf { now - cachedTasteAtMillis < TASTE_CACHE_MS }?.let { return it }
        val tracks = currentLinks()
            .mapNotNull { playlistRepository.getById(it.localPlaylistId) }
            .flatMap { it.tracks }
            .distinctBy { it.key }
        cachedTaste = tracks
        cachedTasteAtMillis = now
        return tracks
    }

    private fun invalidateTaste() {
        cachedTaste = null
    }

    private suspend fun currentLinks(): List<SpotifyLink> =
        decode(dataStore.data.recoverPreferences(TAG).first().readSafely(LINKS_KEY))

    private suspend fun writeLinks(links: List<SpotifyLink>) {
        dataStore.edit { it[LINKS_KEY] = json.encodeToString(links) }
    }

    private fun decode(raw: String?): List<SpotifyLink> =
        raw?.let { runCatching { json.decodeFromString<List<SpotifyLink>>(it) }.getOrNull() }.orEmpty()

    private companion object {
        val LINKS_KEY = stringPreferencesKey("spotify_sync_links")
        const val MIN_SYNC_AGE_MS = 6L * 60 * 60 * 1000
        const val CHECK_INTERVAL_MS = 60L * 60 * 1000
        const val TASTE_CACHE_MS = 10L * 60 * 1000
    }
}
