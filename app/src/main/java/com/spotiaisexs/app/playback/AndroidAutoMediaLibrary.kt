package com.spotiaisexs.app.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import com.spotiaisexs.app.data.connect.ConnectStatus
import com.spotiaisexs.app.data.connect.ConnectSync
import com.spotiaisexs.app.data.connect.JamState
import com.spotiaisexs.app.data.repository.SongPlayStatsRepository
import com.spotiaisexs.app.ui.connect.jamQrMatrix
import com.spotiaisexs.app.data.local.db.DownloadedTrackDao
import com.spotiaisexs.app.data.local.db.DownloadedTrackEntity
import com.spotiaisexs.app.data.playlist.PlaylistRepository
import com.spotiaisexs.app.data.playlist.SavedPlaylist
import com.spotiaisexs.app.data.playlist.isYouTubeOnly
import com.spotiaisexs.app.data.search.SearchRepository
import com.spotiaisexs.app.data.search.SearchTab
import com.spotiaisexs.app.data.ytmusic.YtMusicLibraryManager
import com.spotiaisexs.app.data.generate.youtubeVideoIdOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Driver-safe browse tree shared by Android Auto and the phone player. Same
 * main menu as the app, adapted to the car: four tabs (Inicio, Biblioteca,
 * Cola, Jam) with covers in grids, sections like the app's home, and the
 * Jam's QR shown as a big tile so friends in the car can scan it.
 */
@Singleton
class AndroidAutoMediaLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val ytMusicLibraryManager: YtMusicLibraryManager,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val searchRepository: SearchRepository,
    private val songPlayStats: dagger.Lazy<SongPlayStatsRepository>,
    private val connectSync: dagger.Lazy<ConnectSync>,
) {
    /** Recently played songs as last shown in Inicio, so a tap plays the right one. */
    @Volatile private var recentTracks: List<PlayableTrack> = emptyList()
    @Volatile private var cachedQr: Pair<String, Bitmap>? = null

    /** The Jam (open/closed, guests) — the car's Jam tab refreshes on every change. */
    val jamChanges get() = connectSync.get().jam
    private val nextSearchToken = AtomicLong()
    private val searchQueues = object : LinkedHashMap<String, List<PlayableTrack>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PlayableTrack>>?): Boolean =
            size > MAX_CACHED_SEARCHES
    }
    val playlistChanges: Flow<Unit>
        get() = merge(
            playlistRepository.changes,
            ytMusicLibraryManager.playlists.map { },
        )
    val downloadChanges get() = downloadedTrackDao.getAll()

    suspend fun loadChildren(parentId: String, state: MusicPlayerState): List<MediaBrowserCompat.MediaItem> =
        when {
            parentId == ROOT_ID -> rootItems(state)
            parentId == HOME_ID -> homeItems()
            parentId == JAM_ID -> jamItems()
            parentId == QUEUE_ID -> state.queue.mapIndexed(::queueTrackItem)
            parentId == LIBRARY_ID -> libraryItems()
            parentId == PLAYLISTS_ID -> getMergedPlaylists().map { playlist ->
                val trackCount = playlist.remoteTrackCount ?: playlist.tracks.size
                // Browsable-only on purpose (issue #58): flagging playlists
                // PLAYABLE makes Android Auto play from track 0 on tap
                // instead of opening the track list. Voice/assistant play
                // still works via playMediaId("$PLAYLIST_PREFIX...").
                browsableItem(
                    mediaId = "$PLAYLIST_PREFIX${playlist.id}",
                    title = playlist.title,
                    subtitle = if (playlist.isYouTubeOnly) "YouTube Music • ${trackCountLabel(trackCount)}" else trackCountLabel(trackCount),
                    iconUri = playlist.coverUri(),
                )
            }
            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                val playlist = playlistId?.let { resolvePlaylistWithTracks(it) }
                if (playlist == null || playlist.tracks.isEmpty()) {
                    emptyList()
                } else {
                    playlist.tracks.mapIndexed { index, track ->
                        playableItem(
                            mediaId = "$PLAYLIST_TRACK_PREFIX${playlist.id}:$index",
                            track = track.toPlayableTrack(),
                        )
                    }
                }
            }
            parentId == DOWNLOADS_ID -> downloadedTrackDao.getAllList().map(::downloadItem)
            else -> emptyList()
        }

    suspend fun search(query: String): List<MediaBrowserCompat.MediaItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        // 1. Search matching playlists (local and YouTube Music imported)
        val matchingPlaylists = runCatching {
            getMergedPlaylists().filter { it.title.contains(cleanQuery, ignoreCase = true) }
        }.getOrDefault(emptyList()).map { playlist ->
            val count = playlist.remoteTrackCount ?: playlist.tracks.size
            // Same as the Playlists node above: browsable-only so a tap
            // opens the track list instead of playing from track 0.
            browsableItem(
                mediaId = "$PLAYLIST_PREFIX${playlist.id}",
                title = playlist.title,
                subtitle = if (playlist.isYouTubeOnly) "YouTube Music • ${trackCountLabel(count)}" else trackCountLabel(count),
                iconUri = playlist.coverUri(),
            )
        }

        // 2. Search tracks via InnerTube
        val tracks = searchTracks(cleanQuery)
        val trackItems = if (tracks.isNotEmpty()) {
            val token = nextSearchToken.incrementAndGet().toString(36)
            synchronized(searchQueues) { searchQueues[token] = tracks }
            tracks.mapIndexed { index, track ->
                playableItem("$SEARCH_PREFIX$token:$index", track)
            }
        } else {
            emptyList()
        }

        return (matchingPlaylists + trackItems).take(MAX_SEARCH_RESULTS)
    }

    suspend fun playMediaId(mediaId: String, player: MusicPlayer): Boolean = when {
        mediaId == ACTION_RADIO -> playInfiniteRadio(player)
        mediaId == ACTION_JAM_START -> startJam()
        mediaId == ACTION_JAM_END -> runCatching { connectSync.get().endJam() }.isSuccess
        mediaId == ACTION_JAM_QR -> true
        mediaId.startsWith(RECENT_PREFIX) -> {
            val track = mediaId.removePrefix(RECENT_PREFIX).toIntOrNull()?.let { recentTracks.getOrNull(it) }
            // One song: it plays with its focused radio, exactly like tapping it in the app.
            track?.let { player.play(it, sourceLabel = "Escuchado hace poco"); true } ?: false
        }
        mediaId == QUEUE_ID -> player.state.value.current?.let { player.resume(); true } ?: false
        mediaId == DOWNLOADS_ID -> playDownloads(player)
        mediaId.startsWith(QUEUE_TRACK_PREFIX) -> {
            val index = mediaId.removePrefix(QUEUE_TRACK_PREFIX).toIntOrNull()
            if (index != null && index in player.state.value.queue.indices) {
                player.seekToQueueItem(index)
                true
            } else {
                false
            }
        }
        mediaId.startsWith(PLAYLIST_TRACK_PREFIX) -> {
            val parts = mediaId.removePrefix(PLAYLIST_TRACK_PREFIX).split(':', limit = 2)
            playPlaylist(parts.getOrNull(0)?.toLongOrNull(), parts.getOrNull(1)?.toIntOrNull(), player)
        }
        mediaId.startsWith(PLAYLIST_PREFIX) ->
            playPlaylist(mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull(), 0, player)
        mediaId.startsWith(DOWNLOAD_PREFIX) -> {
            val track = mediaId.removePrefix(DOWNLOAD_PREFIX).toLongOrNull()
                ?.let { downloadedTrackDao.findById(it) }
                ?.toPlayableTrack()
            track?.let { player.play(it, "Downloads"); true } ?: false
        }
        mediaId.startsWith(SEARCH_PREFIX) -> playCachedSearch(mediaId, player)
        else -> false
    }

    suspend fun playSearch(query: String, player: MusicPlayer): Boolean {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return false

        // Check if query directly matches a local or YouTube playlist name
        val matchingPlaylist = runCatching {
            val playlists = getMergedPlaylists()
            playlists.firstOrNull { it.title.equals(cleanQuery, ignoreCase = true) }
                ?: playlists.firstOrNull { it.title.contains(cleanQuery, ignoreCase = true) }
        }.getOrNull()

        if (matchingPlaylist != null) {
            return playPlaylist(matchingPlaylist.id, 0, player)
        }

        val tracks = searchTracks(cleanQuery)
        if (tracks.isEmpty()) return false
        player.playQueue(tracks, sourceLabel = "Android Auto Search: $cleanQuery")
        return true
    }

    private suspend fun getMergedPlaylists(): List<SavedPlaylist> {
        val local = runCatching { playlistRepository.getAll() }.getOrDefault(emptyList())
        val remote = runCatching { ytMusicLibraryManager.playlists.value }.getOrDefault(emptyList())
        val localRemoteIds = local.mapNotNull { it.remotePlaylistId?.removePrefix("VL") }.toSet()
        val filteredRemote = remote.filterNot { it.remotePlaylistId?.removePrefix("VL") in localRemoteIds }
        return (local + filteredRemote).sortedByDescending { it.createdAtMillis }
    }

    private suspend fun resolvePlaylistWithTracks(playlistId: Long): SavedPlaylist? {
        val local = runCatching { playlistRepository.getById(playlistId) }.getOrNull()
        if (local != null && local.tracks.isNotEmpty()) {
            return local
        }
        return runCatching { ytMusicLibraryManager.loadDetail(playlistId) }.getOrNull() ?: local
    }

    /** The four tabs Android Auto shows across the top (the app's own main menu). */
    private fun rootItems(state: MusicPlayerState): List<MediaBrowserCompat.MediaItem> = listOf(
        browsableItem(
            mediaId = HOME_ID,
            title = "Inicio",
            subtitle = "Para ti y lo último que escuchaste",
            iconUri = drawableUri(com.spotiaisexs.app.R.drawable.ic_auto_home),
            extras = styleExtras(browsable = CONTENT_STYLE_GRID, playable = CONTENT_STYLE_GRID),
        ),
        browsableItem(
            mediaId = LIBRARY_ID,
            title = "Biblioteca",
            subtitle = "Me gusta, playlists y descargas",
            iconUri = drawableUri(com.spotiaisexs.app.R.drawable.ic_auto_library),
            extras = styleExtras(browsable = CONTENT_STYLE_GRID, playable = CONTENT_STYLE_LIST),
        ),
        browsableItem(
            mediaId = QUEUE_ID,
            title = "Cola",
            subtitle = if (state.queue.isEmpty()) "No hay nada en la cola" else trackCountLabel(state.queue.size),
            playable = state.current != null,
            iconUri = drawableUri(com.spotiaisexs.app.R.drawable.ic_auto_queue),
            extras = styleExtras(browsable = CONTENT_STYLE_LIST, playable = CONTENT_STYLE_LIST),
        ),
        browsableItem(
            mediaId = JAM_ID,
            title = "Jam",
            subtitle = if (connectSync.get().jam.value?.open == true) "En directo" else "Escuchad juntos",
            iconUri = drawableUri(com.spotiaisexs.app.R.drawable.ic_auto_jam),
            extras = styleExtras(browsable = CONTENT_STYLE_GRID, playable = CONTENT_STYLE_GRID),
        ),
    )

    /** Inicio: "Para ti", "Escuchado hace poco" and "Tus playlists", like the app's home. */
    private suspend fun homeItems(): List<MediaBrowserCompat.MediaItem> {
        val forYou = buildList {
            add(
                actionItem(
                    mediaId = ACTION_RADIO,
                    title = "Radio infinita",
                    subtitle = "Canciones nuevas según tus gustos",
                    iconUri = drawableUri(com.spotiaisexs.app.R.drawable.ic_auto_radio),
                    group = "Para ti",
                ),
            )
            runCatching { playlistRepository.getLikedSongs() }.getOrNull()
                ?.takeIf { it.tracks.isNotEmpty() }
                ?.let { liked ->
                    add(
                        browsableItem(
                            mediaId = "$PLAYLIST_PREFIX${liked.id}",
                            title = "Me gusta",
                            subtitle = trackCountLabel(liked.tracks.size),
                            iconUri = drawableUri(com.spotiaisexs.app.R.drawable.ic_auto_heart),
                            extras = groupExtras("Para ti"),
                        ),
                    )
                }
        }
        val recent = runCatching { songPlayStats.get().recentlyPlayed(RECENT_LIMIT) }.getOrDefault(emptyList())
            .filter { it.title.isNotBlank() }
            .map { PlayableTrack(title = it.title, artist = it.artist, artworkUrl = it.artworkUrl, videoId = it.videoId) }
        recentTracks = recent
        val recentItems = recent.mapIndexed { index, track ->
            playableItem("$RECENT_PREFIX$index", track, extras = groupExtras("Escuchado hace poco"))
        }
        val playlists = getMergedPlaylists()
            .filter { it.mode != com.spotiaisexs.app.data.playlist.LIKED_SONGS_MODE }
            .take(HOME_PLAYLISTS)
            .map { playlist ->
                browsableItem(
                    mediaId = "$PLAYLIST_PREFIX${playlist.id}",
                    title = playlist.title,
                    subtitle = trackCountLabel(playlist.remoteTrackCount ?: playlist.tracks.size),
                    iconUri = playlist.coverUri(),
                    extras = groupExtras("Tus playlists"),
                )
            }
        return forYou + recentItems + playlists
    }

    /** Biblioteca: Me gusta first, then every playlist, then downloads. */
    private suspend fun libraryItems(): List<MediaBrowserCompat.MediaItem> {
        val playlists = getMergedPlaylists().sortedByDescending { it.mode == com.spotiaisexs.app.data.playlist.LIKED_SONGS_MODE }
        return playlists.map { playlist ->
            val liked = playlist.mode == com.spotiaisexs.app.data.playlist.LIKED_SONGS_MODE
            browsableItem(
                mediaId = "$PLAYLIST_PREFIX${playlist.id}",
                title = if (liked) "Me gusta" else playlist.title,
                subtitle = if (playlist.isYouTubeOnly) "YouTube Music · ${trackCountLabel(playlist.remoteTrackCount ?: playlist.tracks.size)}"
                else trackCountLabel(playlist.remoteTrackCount ?: playlist.tracks.size),
                iconUri = if (liked) drawableUri(com.spotiaisexs.app.R.drawable.ic_auto_heart) else playlist.coverUri(),
            )
        } + browsableItem(
            mediaId = DOWNLOADS_ID,
            title = "Descargas",
            subtitle = "Para escuchar sin conexión",
            playable = true,
            iconUri = drawableUri(com.spotiaisexs.app.R.drawable.ic_auto_download),
        )
    }

    /**
     * Jam: with a Jam open, its QR fills a tile (scan it from the passenger
     * seat) plus who is in; otherwise a tile to start one.
     */
    private suspend fun jamItems(): List<MediaBrowserCompat.MediaItem> {
        val sync = connectSync.get()
        val connected = sync.status.value is ConnectStatus.Connected
        val jam = sync.jam.value
        val link = sync.jamLink(jam)
        if (jam != null && jam.open && link != null) {
            val guests = jam.activeGuests()
            return listOf(
                MediaBrowserCompat.MediaItem(
                    MediaDescriptionCompat.Builder()
                        .setMediaId(ACTION_JAM_QR)
                        .setTitle("Escanea para unirte")
                        .setSubtitle(
                            if (guests.isEmpty()) "Jam de ${jam.hostName ?: "SpotiAisexs"} · aún no hay nadie"
                            else "En la Jam: ${guests.joinToString(", ")}",
                        )
                        .apply {
                            val qr = qrBitmap(link)
                            if (qr != null) setIconBitmap(qr) else setIconUri(drawableUri(com.spotiaisexs.app.R.drawable.ic_auto_jam))
                        }
                        .build(),
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
                ),
                actionItem(
                    mediaId = ACTION_JAM_END,
                    title = "Terminar Jam",
                    subtitle = "Tus amigos dejan de poder añadir canciones",
                    iconUri = drawableUri(com.spotiaisexs.app.R.drawable.ic_auto_stop),
                ),
            )
        }
        return listOf(
            actionItem(
                mediaId = ACTION_JAM_START,
                title = "Empezar Jam",
                subtitle = if (connected) "Tus amigos añaden canciones desde un enlace" else "Conecta el móvil con la web en Ajustes → Web y Jam",
                iconUri = drawableUri(com.spotiaisexs.app.R.drawable.ic_auto_jam),
            ),
        )
    }

    private suspend fun startJam(): Boolean {
        val sync = connectSync.get()
        if (sync.status.value !is ConnectStatus.Connected) return false
        val name = runCatching { sync.savedEmail() }.getOrNull()?.substringBefore('@')?.takeIf(String::isNotBlank) ?: "SpotiAisexs"
        return runCatching { sync.startJam(name) }.isSuccess
    }

    private suspend fun playInfiniteRadio(player: MusicPlayer): Boolean {
        val liked = runCatching { playlistRepository.getLikedSongs()?.tracks }.getOrNull().orEmpty()
        val recent = runCatching { songPlayStats.get().recentlyPlayed(30) }.getOrDefault(emptyList())
            .map { PlayableTrack(title = it.title, artist = it.artist, artworkUrl = it.artworkUrl, videoId = it.videoId) }
        val seed = liked.map { it.toPlayableTrack() }.ifEmpty { recent }.randomOrNull()
            ?: player.state.value.current
            ?: return false
        player.play(seed, sourceLabel = "Infinite Radio", startRadio = true, radioStyle = RadioStyle.EXPLORE)
        return true
    }

    private fun JamState.activeGuests(): List<String> {
        val now = System.currentTimeMillis()
        return guests.values.filter { now - it.lastSeen < 90_000 }.map { it.name }.distinct()
    }

    /** White tile, black modules, generous quiet zone: readable on a car screen. */
    private fun qrBitmap(link: String): Bitmap? {
        cachedQr?.takeIf { it.first == link }?.let { return it.second }
        val matrix = jamQrMatrix(link) ?: return null
        val quiet = 4
        val cells = matrix.width + quiet * 2
        val scale = (QR_PX / cells).coerceAtLeast(1)
        val px = cells * scale
        val pixels = IntArray(px * px) { android.graphics.Color.WHITE }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (!matrix[x, y]) continue
            val ox = (x + quiet) * scale
            val oy = (y + quiet) * scale
            for (dy in 0 until scale) {
                val row = (oy + dy) * px
                for (dx in 0 until scale) pixels[row + ox + dx] = android.graphics.Color.BLACK
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
        cachedQr = link to bitmap
        return bitmap
    }

    private suspend fun playPlaylist(id: Long?, index: Int?, player: MusicPlayer): Boolean {
        val playlist = id?.let { resolvePlaylistWithTracks(it) } ?: return false
        val tracks = playlist.tracks.map { it.toPlayableTrack() }
        if (tracks.isEmpty()) return false
        player.playQueue(
            tracks = tracks,
            startIndex = index?.coerceIn(tracks.indices) ?: 0,
            sourceLabel = playlist.title,
        )
        return true
    }

    private suspend fun playDownloads(player: MusicPlayer): Boolean {
        val tracks = downloadedTrackDao.getAllList().map { it.toPlayableTrack() }
        if (tracks.isEmpty()) return false
        player.playQueue(tracks, sourceLabel = "Downloads")
        return true
    }

    private fun playCachedSearch(mediaId: String, player: MusicPlayer): Boolean {
        val parts = mediaId.removePrefix(SEARCH_PREFIX).split(':', limit = 2)
        val token = parts.getOrNull(0) ?: return false
        val index = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val tracks = synchronized(searchQueues) { searchQueues[token] } ?: return false
        if (index !in tracks.indices) return false
        player.playQueue(tracks, startIndex = index, sourceLabel = "Android Auto Search")
        return true
    }

    private suspend fun searchTracks(query: String): List<PlayableTrack> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        return searchRepository.search(SearchTab.TRACKS, cleanQuery)
            .asSequence()
            .filter { it.name.isNotBlank() }
            .map { result ->
                PlayableTrack(
                    title = result.name,
                    artist = result.artist.orEmpty().ifBlank { "Unknown artist" },
                    album = result.subtitle,
                    artworkUrl = result.artworkUrl,
                    videoId = result.videoId,
                )
            }
            .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .take(MAX_SEARCH_RESULTS)
            .toList()
    }

    private fun queueTrackItem(index: Int, track: PlayableTrack) =
        playableItem("$QUEUE_TRACK_PREFIX$index", track)

    private fun downloadItem(track: DownloadedTrackEntity) =
        playableItem("$DOWNLOAD_PREFIX${track.id}", track.toPlayableTrack())

    private fun DownloadedTrackEntity.toPlayableTrack() = PlayableTrack(
        title = title,
        artist = artist,
        album = album.takeIf(String::isNotBlank),
        artworkUrl = artworkUrl,
        playbackUrl = mediaStoreUri?.takeIf(String::isNotBlank)
            ?: Uri.fromFile(File(filePath)).toString(),
        durationMs = durationMs.takeIf { it > 0L },
    )

    private fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String,
        playable: Boolean = false,
        iconUri: Any? = null,
        extras: Bundle? = null,
    ): MediaBrowserCompat.MediaItem {
        val flags = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE or
            if (playable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else 0
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconUri(iconUri.asUri() ?: appIconUri())
                .setExtras(extras)
                .build(),
            flags,
        )
    }

    /** A tile that does something (start the radio, open/close the Jam) when tapped. */
    private fun actionItem(mediaId: String, title: String, subtitle: String, iconUri: Uri, group: String? = null) =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconUri(iconUri)
                .setExtras(group?.let(::groupExtras))
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
        )

    private fun playableItem(mediaId: String, track: PlayableTrack, extras: Bundle? = null) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(track.title)
            .setSubtitle(track.artist)
            .setDescription(track.album)
            .setIconUri(track.coverUri()?.toSafeUri())
            .setExtras(extras)
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
    )

    private fun Any?.asUri(): Uri? = when (this) {
        is Uri -> this
        is String -> toSafeUri()
        else -> null
    }

    private fun drawableUri(res: Int): Uri = Uri.parse("android.resource://${context.packageName}/$res")

    /** How Android Auto lays out a tab's children (grid of covers or list). */
    private fun styleExtras(browsable: Int, playable: Int) = Bundle().apply {
        putInt(CONTENT_STYLE_BROWSABLE_HINT, browsable)
        putInt(CONTENT_STYLE_PLAYABLE_HINT, playable)
    }

    /** Section header in a grid ("Para ti", "Escuchado hace poco"…). Items of one group go together. */
    private fun groupExtras(title: String) = Bundle().apply { putString(CONTENT_STYLE_GROUP_TITLE_HINT, title) }

    private fun appIconUri(): Uri = Uri.parse(
        "android.resource://${context.packageName}/${com.spotiaisexs.app.R.drawable.ic_car_attribution}",
    )

    private fun trackCountLabel(count: Int) = if (count == 1) "1 canción" else "$count canciones"

    /** Search results only carry a videoId when InnerTube matched a song —
     *  fall back to its YouTube thumbnail so Auto still shows a cover. */
    private fun PlayableTrack.coverUri(): String? {
        artworkUrl?.takeIf { it.isNotBlank() }?.let { return it }
        videoId?.takeIf { it.isNotBlank() }?.let { return "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        return null
    }

    /** Best available playlist cover: custom > remote > first track art > first track YouTube thumb. */
    private fun SavedPlaylist.coverUri(): String? {
        customCoverUri?.takeIf { it.isNotBlank() }?.let { return it }
        remoteArtworkUrl?.takeIf { it.isNotBlank() }?.let { return it }
        return tracks.firstNotNullOfOrNull { track ->
            track.artworkUrl?.takeIf { it.isNotBlank() }
                ?: track.youtubeVideoIdOrNull()?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        }
    }

    private fun String.toSafeUri(): Uri? =
        runCatching { Uri.parse(this) }.getOrNull()?.takeIf { it.toString().isNotBlank() }

    companion object {
        const val ROOT_ID = "spotiaisexs_root"
        const val HOME_ID = "category:home"
        const val LIBRARY_ID = "category:library"
        const val JAM_ID = "category:jam"
        const val QUEUE_ID = "category:queue"
        const val PLAYLISTS_ID = "category:playlists"
        const val DOWNLOADS_ID = "category:downloads"
        private const val QUEUE_TRACK_PREFIX = "queue:"
        private const val PLAYLIST_PREFIX = "playlist:"
        private const val PLAYLIST_TRACK_PREFIX = "playlist-track:"
        private const val DOWNLOAD_PREFIX = "download:"
        private const val SEARCH_PREFIX = "search:"
        private const val MAX_CACHED_SEARCHES = 6
        private const val MAX_SEARCH_RESULTS = 30
        private const val RECENT_PREFIX = "recent:"
        private const val ACTION_RADIO = "action:radio"
        private const val ACTION_JAM_START = "action:jam:start"
        private const val ACTION_JAM_END = "action:jam:end"
        private const val ACTION_JAM_QR = "action:jam:qr"
        private const val RECENT_LIMIT = 12
        private const val HOME_PLAYLISTS = 6
        private const val QR_PX = 480
        const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_GROUP_TITLE_HINT = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        const val CONTENT_STYLE_LIST = 1
        const val CONTENT_STYLE_GRID = 2
    }
}
