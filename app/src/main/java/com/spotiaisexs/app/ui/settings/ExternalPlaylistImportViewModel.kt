package com.spotiaisexs.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotiaisexs.app.data.playlist.AppleMusicPlaylistImporter
import com.spotiaisexs.app.data.playlist.ExternalImportResult
import com.spotiaisexs.app.data.playlist.ExternalPlaylistLink
import com.spotiaisexs.app.data.playlist.ExternalPlaylistResult
import com.spotiaisexs.app.data.playlist.ExternalPlaylistSource
import com.spotiaisexs.app.data.playlist.PlaylistImportManager
import com.spotiaisexs.app.data.playlist.SavedPlaylist
import com.spotiaisexs.app.data.playlist.SpotifyPlaylistImporter
import com.spotiaisexs.app.data.spotify.SpotifySyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the "paste a Spotify / Apple Music link" importer.
 *
 * Mirrors [YouTubeImportUiState]: one immutable data class pushed through a
 * single [StateFlow], so the screen is a pure function of it.
 */
data class ExternalImportUiState(
    val link: String = "",
    val isLoading: Boolean = false,
    val preview: ExternalPlaylistResult? = null,
    val isImporting: Boolean = false,
    val progress: String? = null,
    val errorMessage: String? = null,
    val importedCount: Int = 0,
    val lastResult: ExternalImportResult? = null,
    /** Spotify only: keep the imported playlist linked and refreshed automatically. */
    val keepSynced: Boolean = true,
    val linkedSpotifyCount: Int = 0,
    val isSyncingAll: Boolean = false,
    val syncMessage: String? = null,
)

/**
 * Backs [ExternalPlaylistImportScreen]: detects the provider from the pasted
 * link, previews the public playlist's track list, and hands the link to
 * [PlaylistImportManager] for the actual match + save pass.
 */
@HiltViewModel
class ExternalPlaylistImportViewModel @Inject constructor(
    private val importManager: PlaylistImportManager,
    private val spotifyPlaylistImporter: SpotifyPlaylistImporter,
    private val appleMusicPlaylistImporter: AppleMusicPlaylistImporter,
    private val spotifySyncManager: SpotifySyncManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExternalImportUiState())
    val uiState: StateFlow<ExternalImportUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            spotifySyncManager.links.collect { links ->
                _uiState.update { it.copy(linkedSpotifyCount = links.size) }
            }
        }
    }

    fun onKeepSyncedChange(value: Boolean) {
        _uiState.update { it.copy(keepSynced = value) }
    }

    /** Re-reads every linked Spotify playlist right now. */
    fun syncAllNow() {
        if (_uiState.value.isSyncingAll) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingAll = true, syncMessage = null) }
            val message = try {
                val reports = spotifySyncManager.syncAll(force = true)
                val added = reports.sumOf { it.added }
                val removed = reports.sumOf { it.removed }
                "${reports.size} playlists sincronizadas \u2022 +$added / -$removed canciones"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "No se pudo sincronizar: ${e.localizedMessage ?: e.message}"
            }
            _uiState.update { it.copy(isSyncingAll = false, syncMessage = message) }
        }
    }

    fun onLinkChange(value: String) {
        _uiState.update {
            it.copy(
                link = value,
                errorMessage = null,
                preview = if (value.isBlank()) null else it.preview,
            )
        }
    }

    /** Which provider the pasted link points at, or null when unrecognised. */
    fun detectSource(): ExternalPlaylistSource? = ExternalPlaylistLink.detect(_uiState.value.link)

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    /** Clears a loaded preview so the user can paste a different link. */
    fun clearPreview() {
        _uiState.update {
            it.copy(preview = null, lastResult = null, importedCount = 0, progress = null, errorMessage = null)
        }
    }

    /**
     * Resolves the pasted link into a preview (title + track rows) without
     * saving anything — the user confirms with the Import CTA afterwards.
     */
    fun loadPreview() {
        val raw = _uiState.value.link
        if (raw.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify or Apple Music playlist link.") }
            return
        }
        val source = ExternalPlaylistLink.detect(raw)
        if (source == null) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify or Apple Music playlist link.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val result: ExternalPlaylistResult? = when (source) {
                    ExternalPlaylistSource.SPOTIFY -> spotifyPlaylistImporter.fetchPlaylist(raw.trim())
                    ExternalPlaylistSource.APPLE_MUSIC -> appleMusicPlaylistImporter.fetchPlaylist(raw.trim())
                }
                val rows = result?.rows.orEmpty()
                if (rows.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            preview = null,
                            errorMessage = "No tracks found in that playlist. Make sure it is public.",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, preview = result, errorMessage = null, lastResult = null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Couldn't read that playlist: ${e.localizedMessage ?: e.message}",
                    )
                }
            }
        }
    }

    /**
     * Runs the real import: [PlaylistImportManager] re-reads the playlist,
     * matches every row to a playable track and saves the playlist.
     */
    fun import(onSuccess: (SavedPlaylist) -> Unit) {
        if (_uiState.value.isImporting) return
        val raw = _uiState.value.link
        if (raw.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify or Apple Music playlist link.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, progress = "Importing playlist...", errorMessage = null)
            }
            try {
                val linkToSpotify = _uiState.value.keepSynced &&
                    ExternalPlaylistLink.detect(raw) == ExternalPlaylistSource.SPOTIFY
                if (linkToSpotify) {
                    val (saved, report) = spotifySyncManager.link(raw.trim())
                    val matched = report.total - report.unmatched
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            progress = "$matched importadas, ${report.unmatched} sin encontrar \u2022 sincronizada",
                            importedCount = matched,
                        )
                    }
                    onSuccess(saved)
                    return@launch
                }
                val (saved, result) = importManager.importExternalPlaylist(raw.trim())
                val skipped = (result.totalRows - result.matchedCount).coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        progress = "${result.matchedCount} imported, $skipped skipped",
                        importedCount = result.matchedCount,
                        lastResult = result,
                    )
                }
                onSuccess(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        progress = null,
                        errorMessage = "Import failed: ${e.localizedMessage ?: e.message}",
                    )
                }
            }
        }
    }
}
