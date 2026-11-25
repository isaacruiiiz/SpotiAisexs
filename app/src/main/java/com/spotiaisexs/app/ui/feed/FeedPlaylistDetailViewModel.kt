package com.spotiaisexs.app.ui.feed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotiaisexs.app.data.feed.FeedRepository
import com.spotiaisexs.app.data.music.InnerTubeMusicApi
import com.spotiaisexs.app.data.music.YouTubeMusicTrack
import com.spotiaisexs.app.data.music.YouTubePlaylistResult
import com.spotiaisexs.app.data.playlist.PlaylistImportManager
import com.spotiaisexs.app.data.playlist.PlaylistRepository
import com.spotiaisexs.app.playback.MusicPlayer
import com.spotiaisexs.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface FeedPlaylistDetailUiState {
    data object Loading : FeedPlaylistDetailUiState

    @Immutable
    data class Success(
        val playlist: YouTubePlaylistResult,
        val isSaving: Boolean = false,
        val savedToLibrary: Boolean = false,
        val saveError: String? = null,
        val isLoadingMore: Boolean = false,
        val loadError: String? = null,
        /** Only for "Esto es": the artist, and which songs come from the user's listening. */
        val thisIsArtist: String? = null,
        val mineVideoIds: Set<String> = emptySet(),
        /** Built playlists ("Esto es", moods): the small word over the big title, and the big title. */
        val heroKicker: String? = null,
        val heroTitle: String? = null,
        /** What the "Tuya" tag means on this playlist. */
        val mineLegend: String? = null,
    ) : FeedPlaylistDetailUiState

    data class Error(val message: String) : FeedPlaylistDetailUiState
}

@HiltViewModel
class FeedPlaylistDetailViewModel @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val musicPlayer: MusicPlayer,
    private val importManager: PlaylistImportManager,
    private val playlistRepository: PlaylistRepository,
    private val feedRepository: FeedRepository,
    private val thisIsPlaylists: com.spotiaisexs.app.data.thisis.ThisIsPlaylists,
    private val moodPlaylists: com.spotiaisexs.app.data.mood.MoodPlaylists,
) : ViewModel() {
    private val _uiState = MutableStateFlow<FeedPlaylistDetailUiState>(FeedPlaylistDetailUiState.Loading)
    val uiState: StateFlow<FeedPlaylistDetailUiState> = _uiState.asStateFlow()

    private var currentPlaylistId: String? = null

    private var loadJob: Job? = null

    fun load(playlistId: String, force: Boolean = false) {
        if (playlistId.isBlank()) {
            _uiState.value = FeedPlaylistDetailUiState.Error("Esta playlist no está disponible.")
            return
        }
        if (!force && playlistId == currentPlaylistId && _uiState.value !is FeedPlaylistDetailUiState.Error) return
        loadJob?.cancel()
        val previous = if (playlistId == currentPlaylistId) _uiState.value as? FeedPlaylistDetailUiState.Success else null
        currentPlaylistId = playlistId
        _uiState.value = previous?.copy(isLoadingMore = true, loadError = null) ?: FeedPlaylistDetailUiState.Loading
        if (com.spotiaisexs.app.data.thisis.ThisIsPlaylists.isThisIs(playlistId)) {
            loadJob = viewModelScope.launch { loadThisIs(playlistId) }
            return
        }
        if (com.spotiaisexs.app.data.mood.MoodPlaylists.isMood(playlistId)) {
            loadJob = viewModelScope.launch { loadMood(playlistId) }
            return
        }
        loadJob = viewModelScope.launch {
            val liked = playlistId == "yt_liked"
            val recent = playlistId == "yt_recent"
            val title = if (liked) "Me gusta en YouTube" else if (recent) "Escuchado hace poco" else "Playlist"
            val author = if (liked) "Tus favoritas" else "YouTube Music"
            fun showTracks(tracks: List<YouTubeMusicTrack>) {
                coroutineContext.ensureActive()
                if (tracks.isEmpty()) return
                val state = _uiState.value as? FeedPlaylistDetailUiState.Success
                // Keep the larger warm snapshot until the full fetch catches up.
                if (state != null && state.playlist.tracks.size > tracks.size) return
                _uiState.value = FeedPlaylistDetailUiState.Success(
                    YouTubePlaylistResult(id = playlistId, title = title, author = author,
                        artworkUrl = tracks.firstOrNull()?.artworkUrl, trackCount = tracks.size, tracks = tracks),
                    isLoadingMore = true,
                )
            }
            if (liked) showTracks(feedRepository.getCachedFeed()?.ytLikedSongs.orEmpty())
            if (recent) showTracks(feedRepository.getCachedFeed()?.ytRecentSongs.orEmpty())
            try {
                val result = if (recent) {
                    val taste = innerTube.fetchTasteSignals(recentLimit = 50, likedLimit = 0, feedLimit = 0)
                    taste.recentTracks.takeIf { it.isNotEmpty() }?.let { tracks ->
                        YouTubePlaylistResult(id = playlistId, title = title, author = author,
                            artworkUrl = tracks.firstOrNull()?.artworkUrl, trackCount = tracks.size, tracks = tracks)
                    }
                } else {
                    innerTube.fetchPlaylist(if (liked) "LM" else playlistId, progressive = true, onPageLoaded = ::showTracks)
                }
                coroutineContext.ensureActive()
                val current = _uiState.value as? FeedPlaylistDetailUiState.Success
                if (result == null && current == null) {
                    throw java.io.IOException("No se pudo terminar de cargar la playlist. Reintenta.")
                }
                if (result != null) {
                    _uiState.value = FeedPlaylistDetailUiState.Success(
                        result.copy(id = playlistId, title = if (liked || recent) title else result.title.ifBlank { title },
                            author = result.author?.takeIf(String::isNotBlank) ?: author),
                        // Truncated continuation pages still show what loaded, with
                        // an inline retry for the remainder (fixes logged-out
                        // playlists that previously collapsed to a dead Error).
                        isLoadingMore = false,
                        loadError = if (result.isComplete) null else "Faltan canciones por cargar.",
                    )
                } else {
                    _uiState.value = current?.copy(
                        isLoadingMore = false,
                        loadError = "No se pudo actualizar la playlist.",
                    ) ?: FeedPlaylistDetailUiState.Error("No se pudo terminar de cargar la playlist. Reintenta.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val state = _uiState.value as? FeedPlaylistDetailUiState.Success
                val message = error.message ?: "No se pudo terminar de cargar. Reintenta."
                _uiState.value = state?.copy(isLoadingMore = false, loadError = message)
                    ?: FeedPlaylistDetailUiState.Error(message)
            }
        }
    }

    /** "Esto es {artista}": built here from the artist's hits and the user's own listening. */
    private suspend fun loadThisIs(playlistId: String) {
        try {
            val detail = thisIsPlaylists.buildDetail(playlistId)
            kotlin.coroutines.coroutineContext.ensureActive()
            _uiState.value = FeedPlaylistDetailUiState.Success(
                detail.playlist,
                thisIsArtist = detail.artistName,
                mineVideoIds = detail.mineVideoIds,
                heroKicker = "Esto es",
                heroTitle = detail.artistName,
                mineLegend = "de las que más le escuchas",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.value = FeedPlaylistDetailUiState.Error(error.message ?: "No se pudo montar la playlist. Reintenta.")
        }
    }

    /** Mood playlists ("Gym", "Años 80"…): the user's songs that fit the mood, plus new ones like them. */
    private suspend fun loadMood(playlistId: String) {
        try {
            val detail = moodPlaylists.build(playlistId)
            kotlin.coroutines.coroutineContext.ensureActive()
            _uiState.value = FeedPlaylistDetailUiState.Success(
                detail.playlist,
                mineVideoIds = detail.mineVideoIds,
                heroKicker = "Para cada momento",
                heroTitle = detail.mood.title,
                mineLegend = "las tuyas; el resto son nuevas, parecidas a las tuyas",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.value = FeedPlaylistDetailUiState.Error(error.message ?: "No se pudo montar la playlist. Reintenta.")
        }
    }

    fun saveToLibrary() {
        val current = _uiState.value as? FeedPlaylistDetailUiState.Success ?: return
        if (current.isSaving || current.savedToLibrary) return
        if (current.isLoadingMore || current.loadError != null) {
            _uiState.value = current.copy(saveError = "Espera a que cargue entera para guardarla.")
            return
        }
        val playlistId = currentPlaylistId
        _uiState.value = current.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            try {
                val saved = importManager.importYouTubePlaylist(
                    current.playlist.copy(title = current.playlist.title.ifBlank { "YouTube playlist" }),
                )
                val persisted = runCatching { playlistRepository.getById(saved.id) }.getOrNull()
                if (currentPlaylistId == playlistId) {
                    _uiState.value = if (persisted != null) current.copy(savedToLibrary = true)
                    else current.copy(saveError = "No se pudo guardar la playlist. Vuelve a intentarlo.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (currentPlaylistId == playlistId) {
                    _uiState.value = current.copy(saveError = "No se pudo guardar la playlist. Vuelve a intentarlo.")
                }
            }
        }
    }

    fun playFrom(index: Int) {
        val playlist = (_uiState.value as? FeedPlaylistDetailUiState.Success)?.playlist ?: return
        if (playlist.tracks.isEmpty()) return
        musicPlayer.playQueue(
            tracks = playlist.tracks.map { it.toPlayableTrack() },
            startIndex = index.coerceIn(0, playlist.tracks.lastIndex),
            sourceLabel = playlist.title.ifBlank { "Feed mix" },
        )
    }

    fun shuffle() {
        val playlist = (_uiState.value as? FeedPlaylistDetailUiState.Success)?.playlist ?: return
        if (playlist.tracks.isEmpty()) return
        musicPlayer.playQueue(
            tracks = playlist.tracks.shuffled().map { it.toPlayableTrack() },
            startIndex = 0,
            sourceLabel = playlist.title.ifBlank { "Feed mix" },
        )
    }

    private fun YouTubeMusicTrack.toPlayableTrack() = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId,
        durationMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L),
    )

}
