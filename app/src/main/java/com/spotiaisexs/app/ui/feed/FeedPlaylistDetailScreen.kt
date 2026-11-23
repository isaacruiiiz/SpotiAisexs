package com.spotiaisexs.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotiaisexs.app.data.music.YouTubeMusicTrack
import com.spotiaisexs.app.playback.PlayableTrack
import com.spotiaisexs.app.ui.common.ArtworkImage
import com.spotiaisexs.app.ui.common.DetailActionRow
import com.spotiaisexs.app.ui.common.DetailHero
import com.spotiaisexs.app.ui.common.DetailMeta
import com.spotiaisexs.app.ui.common.DetailPlayButton
import com.spotiaisexs.app.ui.common.DetailTag
import com.spotiaisexs.app.ui.common.DetailTopBar
import com.spotiaisexs.app.ui.common.DetailTrackRow
import com.spotiaisexs.app.ui.common.ExpressiveLoadingIndicator
import com.spotiaisexs.app.ui.common.TrackContextMenuSheet
import com.spotiaisexs.app.ui.common.TrackMenuCapabilities
import com.spotiaisexs.app.ui.common.TrackMenuTarget
import com.spotiaisexs.app.ui.common.TrackMiniTrayData
import com.spotiaisexs.app.ui.common.TrackMiniTraySheet
import com.spotiaisexs.app.ui.common.adaptiveContentWidth
import com.spotiaisexs.app.ui.common.detailTitleStyle
import com.spotiaisexs.app.ui.common.safeDrawingBottomPadding
import com.spotiaisexs.app.ui.common.songCountLabel
import com.spotiaisexs.app.ui.player.LocalMiniPlayerScrollClearance
import com.spotiaisexs.app.ui.player.LocalMusicPlayer
import com.spotiaisexs.app.ui.radio.DialOutlinedButton

@Composable
fun FeedPlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit,
    viewModel: FeedPlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val heroEnd = with(LocalDensity.current) { 220.dp.toPx() }
    val scrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > heroEnd }
    }

    LaunchedEffect(playlistId) {
        viewModel.load(playlistId)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        val success = state as? FeedPlaylistDetailUiState.Success
        when (val current = state) {
            FeedPlaylistDetailUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingIndicator(message = "Abriendo la playlist…")
                }
            }

            is FeedPlaylistDetailUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                    DialOutlinedButton("Reintentar", onClick = { viewModel.load(playlistId) }, accent = true)
                }
            }

            is FeedPlaylistDetailUiState.Success -> {
                PlaylistContent(
                    state = current,
                    listState = listState,
                    onPlay = viewModel::playFrom,
                    onShuffle = viewModel::shuffle,
                    onSave = viewModel::saveToLibrary,
                    onRetry = { viewModel.load(playlistId, force = true) },
                    modifier = Modifier.adaptiveContentWidth(maxWidth = 860.dp),
                )
            }
        }
        DetailTopBar(
            title = success?.playlist?.title?.takeIf(String::isNotBlank) ?: "Playlist",
            // Without a picture behind it the bar has nothing to float on.
            scrolled = scrolled || success == null,
            onBack = onBack,
        )
    }
}

@Composable
private fun PlaylistContent(
    state: FeedPlaylistDetailUiState.Success,
    listState: LazyListState,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlist = state.playlist
    val thisIsArtist = state.thisIsArtist
    val heroTitle = state.heroTitle
    val musicPlayer = LocalMusicPlayer.current
    val playback by musicPlayer.chromeState.collectAsStateWithLifecycle()
    val title = playlist.title.ifBlank { "Playlist" }
    // Same label the view model gives the queue when it plays this playlist.
    val isThisSource = playback.current != null && playback.sourceLabel == playlist.title.ifBlank { "Feed mix" }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var menuTrack by remember { mutableStateOf<YouTubeMusicTrack?>(null) }
    // Long-press (deep press) opens the mini tray; 3-dot keeps the full sheet.
    var miniTrayIndex by remember { mutableStateOf<Int?>(null) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
        ),
    ) {
        item(key = "hero") {
            DetailHero(
                height = 340.dp + statusBar,
                scrollOffset = {
                    if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 0f
                },
                image = {
                    ArtworkImage(
                        name = thisIsArtist ?: playlist.title,
                        artist = thisIsArtist ?: playlist.author.orEmpty(),
                        embeddedUrl = playlist.artworkUrl ?: playlist.tracks.firstOrNull()?.artworkUrl,
                        fallbackIcon = when {
                            thisIsArtist != null -> Icons.Filled.Person
                            playlist.id == "yt_liked" -> Icons.Filled.Favorite
                            else -> Icons.Filled.MusicNote
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            ) {
                if (heroTitle != null) {
                    state.heroKicker?.let { kicker ->
                        Text(
                            kicker,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        heroTitle,
                        style = detailTitleStyle(heroTitle),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        title,
                        style = detailTitleStyle(title),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item(key = "meta") {
            val count = songCountLabel(playlist.tracks.size)
            val author = playlist.author?.takeIf(String::isNotBlank)
            DetailMeta(
                when {
                    heroTitle != null && author != null -> "$author. $count."
                    author != null -> "$author, $count"
                    else -> count
                },
            )
        }

        item(key = "actions") {
            DetailActionRow {
                DialOutlinedButton(
                    text = when {
                        state.isSaving -> "Guardando…"
                        state.savedToLibrary -> "Guardada"
                        else -> "Guardar"
                    },
                    onClick = onSave,
                    leading = {
                        Icon(
                            if (state.savedToLibrary) Icons.Filled.Check else Icons.Filled.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                DialOutlinedButton(
                    text = "Aleatorio",
                    onClick = onShuffle,
                    leading = { Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                Spacer(Modifier.weight(1f))
                DetailPlayButton(
                    playing = isThisSource && playback.isPlaying,
                    description = if (isThisSource && playback.isPlaying) "Pausar" else "Reproducir $title",
                    enabled = playlist.tracks.isNotEmpty(),
                    onClick = { if (isThisSource) musicPlayer.togglePlayPause() else onPlay(0) },
                )
            }
        }

        state.saveError?.let { message ->
            item(key = "save_error") {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                )
            }
        }

        if (state.isLoadingMore || state.loadError != null) {
            item(key = "load_state") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.loadError ?: "Cargando el resto de canciones…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.loadError != null) {
                        DialOutlinedButton("Reintentar", onClick = onRetry, accent = true)
                    }
                }
            }
        }

        item(key = "legend") {
            val legend = state.mineLegend
            if (legend != null && state.mineVideoIds.isNotEmpty()) {
                Row(
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailTag("Tuya")
                    Spacer(Modifier.width(8.dp))
                    Text(
                        legend,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }

        itemsIndexed(
            items = playlist.tracks,
            key = { index, track -> "${track.videoId}:$index" },
            contentType = { _, _ -> "track" },
        ) { index, track ->
            val isPlayingThis = playback.current?.title.equals(track.title, ignoreCase = true) &&
                playback.current?.artist.equals(track.artist, ignoreCase = true)
            DetailTrackRow(
                title = track.title,
                subtitle = track.artist.ifBlank { track.album.orEmpty() },
                artworkUrl = track.artworkUrl,
                playing = isPlayingThis,
                onClick = { onPlay(index) },
                onLongClick = { miniTrayIndex = index },
                onMenu = { menuTrack = track },
                onQueue = { musicPlayer.addToQueue(track.asPlayable()) },
                trailing = if (track.videoId in state.mineVideoIds) {
                    { DetailTag("Tuya") }
                } else {
                    null
                },
            )
        }
    }

    menuTrack?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.title, track.artist, ""),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = false),
            playableTrack = track.asPlayable(),
            playbackSourceLabel = playlist.title.ifBlank { "Mix" },
            onDismiss = { menuTrack = null },
        )
    }

    miniTrayIndex?.let { trayIndex ->
        playlist.tracks.getOrNull(trayIndex)?.let { track ->
            TrackMiniTraySheet(
                data = TrackMiniTrayData(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    artworkUrl = track.artworkUrl,
                    videoId = track.videoId.takeIf(String::isNotBlank),
                    sourceLabel = playlist.title.ifBlank { "Mix" },
                    onPlay = { onPlay(trayIndex) },
                ),
                onDismiss = { miniTrayIndex = null },
            )
        }
    }
}

private fun YouTubeMusicTrack.asPlayable() = PlayableTrack(
    title = title,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
    videoId = videoId.takeIf(String::isNotBlank),
    durationMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L),
)
