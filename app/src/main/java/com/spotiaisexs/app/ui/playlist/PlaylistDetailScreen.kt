package com.spotiaisexs.app.ui.playlist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotiaisexs.app.data.generate.GeneratedTrack
import com.spotiaisexs.app.data.playlist.LIKED_SONGS_MODE
import com.spotiaisexs.app.data.playlist.SavedPlaylist
import com.spotiaisexs.app.data.playlist.isYouTubeOnly
import com.spotiaisexs.app.playback.toPlayableTrack
import com.spotiaisexs.app.ui.common.DetailActionRow
import com.spotiaisexs.app.ui.common.DetailBarButton
import com.spotiaisexs.app.ui.common.DetailHero
import com.spotiaisexs.app.ui.common.DetailMeta
import com.spotiaisexs.app.ui.common.DetailPlayButton
import com.spotiaisexs.app.ui.common.DetailTopBar
import com.spotiaisexs.app.ui.common.DetailTrackRow
import com.spotiaisexs.app.ui.common.detailTitleStyle
import com.spotiaisexs.app.ui.common.songCountLabel
import com.spotiaisexs.app.ui.radio.DialOutlinedButton
import com.spotiaisexs.app.ui.common.ExpressiveLoadingIndicator
import com.spotiaisexs.app.ui.common.PlaylistCover
import com.spotiaisexs.app.ui.common.TrackContextMenuSheet
import com.spotiaisexs.app.ui.common.TrackMenuCapabilities
import com.spotiaisexs.app.ui.common.TrackMenuTarget
import com.spotiaisexs.app.ui.common.adaptiveContentWidth
import com.spotiaisexs.app.ui.shell.FloatingNavDefaults
import com.spotiaisexs.app.ui.theme.ExpressivePillShape
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class PlaylistTrackSort(val label: String) {
    CUSTOM("Orden personalizado"),
    DATE_ADDED("Fecha de añadido"),
    NAME("Título"),
    ARTIST("Artista"),
    PLAY_TIME("Más escuchadas"),
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onOpenPlaylist: ((Long) -> Unit)? = null,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val musicPlayer = com.spotiaisexs.app.ui.player.LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()

    LaunchedEffect(playlistId) {
        viewModel.loadDetail(playlistId)
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.dismissToast()
        }
    }

    val currentFound = state.detailPlaylist?.takeIf { it.id == playlistId }
        ?: state.playlists.firstOrNull { it.id == playlistId }
    var cachedPlaylist by remember(playlistId) {
        mutableStateOf(currentFound)
    }
    LaunchedEffect(currentFound) {
        if (currentFound != null) cachedPlaylist = currentFound
    }
    val playlist = currentFound ?: cachedPlaylist

    if (playlist == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ExpressiveLoadingIndicator(message = "Cargando la playlist…")
        }
        return
    }

    val isThisPlaylistPlaying = playbackState.isPlaying && playbackState.sourceLabel == playlist.title
    var coverEditorOpen by remember { mutableStateOf(false) }
    var coverPickerPending by remember { mutableStateOf(false) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setCustomCover(playlistId, uri.toString())
        }
        coverPickerPending = false
    }

    var menuTarget by remember { mutableStateOf<GeneratedTrack?>(null) }
    var overflowMenuOpen by remember { mutableStateOf(false) }
    val syncedPlaylistIds by viewModel.syncedPlaylistIds.collectAsStateWithLifecycle()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var currentSort by remember { mutableStateOf(PlaylistTrackSort.CUSTOM) }
    var sortAscending by remember { mutableStateOf(true) }
    var isReorderLocked by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val displayTracks = remember(playlist.tracks, currentSort, sortAscending, searchQuery) {
        val sorted = when (currentSort) {
            PlaylistTrackSort.CUSTOM -> if (sortAscending) playlist.tracks else playlist.tracks.reversed()
            PlaylistTrackSort.DATE_ADDED -> if (sortAscending) playlist.tracks else playlist.tracks.reversed()
            PlaylistTrackSort.NAME -> if (sortAscending) {
                playlist.tracks.sortedBy { it.name.lowercase() }
            } else {
                playlist.tracks.sortedByDescending { it.name.lowercase() }
            }
            PlaylistTrackSort.ARTIST -> if (sortAscending) {
                playlist.tracks.sortedBy { it.artist.lowercase() }
            } else {
                playlist.tracks.sortedByDescending { it.artist.lowercase() }
            }
            PlaylistTrackSort.PLAY_TIME -> if (sortAscending) {
                playlist.tracks.sortedBy { it.playcount ?: it.listeners ?: 0L }
            } else {
                playlist.tracks.sortedByDescending { it.playcount ?: it.listeners ?: 0L }
            }
        }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            sorted.filter { it.name.lowercase().contains(q) || it.artist.lowercase().contains(q) }
        } else {
            sorted
        }
    }

    val listState = rememberLazyListState()
    val dragScope = rememberCoroutineScope()
    var draggingIndex by remember(playlistId) { mutableIntStateOf(-1) }
    var dragOffsetY by remember(playlistId) { mutableFloatStateOf(0f) }
    // Single throttled edge auto-scroll job. The old code launched a fresh
    // `dragScope.launch { scrollBy() }` on EVERY drag callback inside the edge
    // zone — dozens of concurrent scrollBy() mutators racing layoutInfo reads
    // and moveTrack() recompositions, which kills the app at the viewport edge.
    var edgeScrollJob by remember(playlistId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun cancelEdgeScroll() {
        edgeScrollJob?.cancel()
        edgeScrollJob = null
    }
    // Permanent reorder only makes sense on the stored order: Custom (ascending)
    // on a local playlist with the lock opened.
    val reorderEnabled = !isReorderLocked &&
        !playlist.isYouTubeOnly &&
        currentSort == PlaylistTrackSort.CUSTOM &&
        sortAscending
    // Stable content keys aligned to the DISPLAYED order (not playlist.tracks
    // order) so animateItem() never sees duplicate or shifting keys at the
    // scroll edge. The old code built keys from playlist.tracks but consumed
    // them by displayTracks index — reversed/sorted lists then reused the
    // wrong key per row, which crashes Lazy layout ("key was already used" /
    // anchor out of bounds) on fast edge flings. Duplicates get occurrence
    // suffixes; album disambiguates same name+artist across releases.
    val displayKeys = remember(displayTracks) {
        val counts = mutableMapOf<String, Int>()
        displayTracks.map { track ->
            val base = if (track.url.isNotBlank()) {
                "url:${track.url}"
            } else {
                "t:${track.name.lowercase()}|${track.artist.lowercase()}|${track.album?.lowercase().orEmpty()}"
            }
            val n = counts.getOrDefault(base, 0)
            counts[base] = n + 1
            "$base#$n"
        }
    }
    LaunchedEffect(playlist.tracks.size, displayTracks.size) {
        if (draggingIndex < 0 || draggingIndex >= displayTracks.size) {
            cancelEdgeScroll()
            draggingIndex = -1
            dragOffsetY = 0f
        }
    }
    val density = LocalDensity.current
    // The bar turns solid once most of the cover has scrolled away.
    val heroEnd = with(density) { 240.dp.toPx() }
    val showScrolledHeader by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > heroEnd
        }
    }

    // The queue carries the playlist's title as its label: that is how we know it is this one sounding.
    val isThisSource = playbackState.current != null && playbackState.sourceLabel == playlist.title
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val songCount = if (playlist.isYouTubeOnly) playlist.remoteTrackCount ?: playlist.tracks.size else playlist.tracks.size
    fun locatePlayingSong() {
        val playingTrack = playbackState.current ?: return
        val index = displayTracks.indexOfFirst {
            it.name.equals(playingTrack.title, ignoreCase = true) && it.artist.equals(playingTrack.artist, ignoreCase = true)
        }
        // +1 for the hero section
        if (index >= 0) dragScope.launch { listState.animateScrollToItem(index + 1) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = FloatingNavDefaults.contentBottomPadding()),
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp)
                .align(Alignment.TopCenter),
        ) {
            // Everything above the songs is ONE item: the reorder drag counts
            // on the songs starting at list position 1.
            item(key = "hero_section") {
                Column(Modifier.fillMaxWidth()) {
                    // The cover from edge to edge, with the title on the fade.
                    DetailHero(
                        height = 340.dp + statusBar,
                        scrollOffset = {
                            if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 0f
                        },
                        image = {
                            PlaylistCover(
                                playlist = playlist,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 0.dp,
                            )
                        },
                    ) {
                        Text(
                            text = playlist.title,
                            style = detailTitleStyle(playlist.title),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    DetailMeta(
                        (if (playlist.isYouTubeOnly) "YouTube Music" else "Playlist") + ", " + songCountLabel(songCount),
                    )

                    // Outlined actions on the left, the one filled button (play) on the right.
                    DetailActionRow {
                        DialOutlinedButton(
                            text = "Aleatorio",
                            accent = isThisSource && playbackState.shuffleEnabled,
                            onClick = {
                                if (isThisSource) {
                                    musicPlayer.toggleShuffle()
                                } else if (displayTracks.isNotEmpty()) {
                                    val playableTracks = displayTracks.map(GeneratedTrack::toPlayableTrack)
                                    musicPlayer.playQueue(
                                        playableTracks,
                                        startIndex = playableTracks.indices.random(),
                                        sourceLabel = playlist.title,
                                        startShuffled = true,
                                    )
                                }
                            },
                            leading = { Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        DialOutlinedButton(
                            text = "Descargar",
                            onClick = { viewModel.downloadPlaylist(playlistId) },
                            leading = { Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        Spacer(Modifier.weight(1f))
                        DetailPlayButton(
                            playing = isThisPlaylistPlaying,
                            description = if (isThisPlaylistPlaying) "Pausar" else "Reproducir ${playlist.title}",
                            enabled = displayTracks.isNotEmpty(),
                            onClick = {
                                if (isThisSource) {
                                    musicPlayer.togglePlayPause()
                                } else {
                                    musicPlayer.playQueue(
                                        displayTracks.map(GeneratedTrack::toPlayableTrack),
                                        startIndex = 0,
                                        sourceLabel = playlist.title,
                                    )
                                }
                            },
                        )
                    }

                    // Order of the songs, and the lock that lets you drag them.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 10.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button) { sortMenuOpen = true }
                                    .height(44.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                    contentDescription = if (sortAscending) "Ascendente" else "Descendente",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = currentSort.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false },
                                shape = RoundedCornerShape(10.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.widthIn(min = 210.dp),
                            ) {
                                PlaylistTrackSort.entries.forEach { option ->
                                    val isSelected = currentSort == option
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = option.label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                )
                                                Spacer(Modifier.width(16.dp))
                                                Icon(
                                                    imageVector = when {
                                                        isSelected && !sortAscending -> Icons.Filled.ArrowDownward
                                                        isSelected && sortAscending -> Icons.Filled.ArrowUpward
                                                        else -> Icons.Filled.SwapVert
                                                    },
                                                    contentDescription = null,
                                                    tint = if (isSelected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                    },
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        },
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (currentSort == option) {
                                                sortAscending = !sortAscending
                                            } else {
                                                currentSort = option
                                                sortAscending = option != PlaylistTrackSort.PLAY_TIME
                                            }
                                            sortMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        if (!playlist.isYouTubeOnly) {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isReorderLocked = !isReorderLocked
                                },
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    imageVector = if (isReorderLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    contentDescription = if (isReorderLocked) "Desbloquear para reordenar" else "Bloquear el orden",
                                    tint = if (isReorderLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = isSearchActive,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Buscar en la playlist") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Borrar búsqueda")
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 4.dp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }

            // Track items
            if (displayTracks.isEmpty()) {
                item(key = "empty_tracks", contentType = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isDetailLoading) {
                            ExpressiveLoadingIndicator(message = "Cargando canciones…")
                        } else {
                            Text(
                                if (searchQuery.isNotBlank()) "Ninguna canción coincide con la búsqueda." else "Esta playlist todavía no tiene canciones.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                if (!isReorderLocked && !reorderEnabled && !playlist.isYouTubeOnly && displayTracks.size > 1) {
                    item(key = "reorder_sort_hint", contentType = "hint") {
                        Text(
                            "Cambia a Orden personalizado, de arriba abajo, para reordenar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                }
                itemsIndexed(
                    items = displayTracks,
                    key = { index, _ ->
                        // Never fall back to a bare "track_$index": two rows
                        // would share one key after insert/delete and Lazy
                        // throws at the viewport edge. displayKeys is always
                        // exactly displayTracks.size long (same remember input).
                        displayKeys.getOrElse(index) { "pos_fallback_$index" }
                    },
                    contentType = { _, _ -> "playlist_track" },
                ) { index, track ->
                    val isPlayingThisSong = playbackState.current?.title.equals(track.name, ignoreCase = true) &&
                        playbackState.current?.artist.equals(track.artist, ignoreCase = true)
                    val isDragging = reorderEnabled && index == draggingIndex
                    val stableKey = displayKeys.getOrElse(index) { "pos_fallback_$index" }

                    Box(
                        modifier = Modifier
                            .animateItem()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffsetY else 0f
                                shadowElevation = if (isDragging) 18f else 0f
                                val s = if (isDragging) 1.025f else 1f
                                scaleX = s
                                scaleY = s
                                alpha = if (isDragging) 0.96f else 1f
                            },
                    ) {
                        DetailTrackRow(
                            title = track.name,
                            subtitle = track.artist,
                            artworkUrl = track.artworkUrl,
                            playing = isPlayingThisSong,
                            onClick = {
                                if (displayTracks.isEmpty()) return@DetailTrackRow
                                // index is from composition time; the list can
                                // shrink (remove/move) before the tap lands —
                                // an OOB startIndex crashed playback at the edge.
                                val safeIndex = index.coerceIn(displayTracks.indices)
                                runCatching {
                                    musicPlayer.playQueue(
                                        displayTracks.map(GeneratedTrack::toPlayableTrack),
                                        startIndex = safeIndex,
                                        sourceLabel = playlist.title,
                                        startShuffled = playbackState.shuffleEnabled,
                                    )
                                }
                            },
                            // While reordering, the row only drags: no menu on long press, no slide to the queue.
                            onLongClick = if (reorderEnabled) null else ({ menuTarget = track }),
                            onMenu = { menuTarget = track },
                            onQueue = if (reorderEnabled) null else ({ musicPlayer.addToQueue(track.toPlayableTrack()) }),
                            trailing = if (!reorderEnabled) {
                                null
                            } else {
                                {
                                    Icon(
                                        Icons.Filled.DragHandle,
                                        stringResource(com.spotiaisexs.app.R.string.queue_drag_hint),
                                        tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            // The whole 44 dp square takes the drag, not just the drawn handle.
                                            .size(44.dp)
                                            .pointerInput(stableKey) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        draggingIndex = index
                                                        dragOffsetY = 0f
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onDragEnd = {
                                                        cancelEdgeScroll()
                                                        draggingIndex = -1
                                                        dragOffsetY = 0f
                                                    },
                                                    onDragCancel = {
                                                        cancelEdgeScroll()
                                                        draggingIndex = -1
                                                        dragOffsetY = 0f
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        val source = draggingIndex
                                                        if (source < 0 || source !in displayTracks.indices) {
                                                            cancelEdgeScroll()
                                                            return@detectDragGestures
                                                        }
                                                        dragOffsetY += dragAmount.y
                                                        // layoutInfo can throw while the list is
                                                        // recomposing under moveTrack(); never let a
                                                        // scroll-edge read crash the app.
                                                        val layoutInfo = runCatching { listState.layoutInfo }.getOrNull()
                                                            ?: return@detectDragGestures
                                                        val visible = runCatching { layoutInfo.visibleItemsInfo }.getOrNull()
                                                            ?: return@detectDragGestures
                                                        if (visible.isEmpty()) return@detectDragGestures
                                                        val draggedInfo = visible
                                                            .firstOrNull { it.index == source + 1 }
                                                            ?: return@detectDragGestures
                                                        val draggedCenter = draggedInfo.offset + draggedInfo.size / 2 + dragOffsetY.toInt()
                                                        // +1 offsets the hero header item at position 0.
                                                        val target = visible.firstOrNull { info ->
                                                            val trackIndex = info.index - 1
                                                            info.index != source + 1 &&
                                                                trackIndex in displayTracks.indices &&
                                                                draggedCenter in info.offset..(info.offset + info.size)
                                                        }?.index?.minus(1)
                                                        if (target != null && target != source && target in displayTracks.indices) {
                                                            runCatching { viewModel.moveTrack(playlistId, source, target) }
                                                            val targetInfo = runCatching {
                                                                listState.layoutInfo.visibleItemsInfo
                                                                    .firstOrNull { it.index == target + 1 }
                                                            }.getOrNull()
                                                            if (targetInfo != null) {
                                                                dragOffsetY += (draggedInfo.offset - targetInfo.offset).toFloat()
                                                            }
                                                            draggingIndex = target
                                                            runCatching { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
                                                        }
                                                        val viewportStart = runCatching { layoutInfo.viewportStartOffset }.getOrDefault(0)
                                                        val viewportEnd = runCatching { layoutInfo.viewportEndOffset }.getOrDefault(0)
                                                        if (viewportEnd <= viewportStart) {
                                                            cancelEdgeScroll()
                                                            return@detectDragGestures
                                                        }
                                                        val edgeZone = 180
                                                        val direction = when {
                                                            draggedCenter < viewportStart + edgeZone -> -1f
                                                            draggedCenter > viewportEnd - edgeZone -> 1f
                                                            else -> 0f
                                                        }
                                                        if (direction == 0f) {
                                                            cancelEdgeScroll()
                                                        } else if (edgeScrollJob?.isActive != true) {
                                                            edgeScrollJob = dragScope.launch {
                                                                runCatching { listState.scrollBy(direction * 28f) }
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                            .padding(10.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        // 2. Bar over the cover: round buttons on the picture, solid with the title once scrolled.
        DetailTopBar(
            title = playlist.title,
            scrolled = showScrolledHeader,
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .adaptiveContentWidth(maxWidth = 860.dp),
        ) {
            if (showScrolledHeader && isThisSource) {
                DetailBarButton(
                    icon = Icons.Rounded.MyLocation,
                    description = "Ir a la canción que suena",
                    scrolled = true,
                    onClick = { locatePlayingSong() },
                )
            }
            DetailBarButton(
                icon = Icons.Filled.Search,
                description = "Buscar en la playlist",
                scrolled = showScrolledHeader,
                active = isSearchActive,
                onClick = {
                    isSearchActive = !isSearchActive
                    if (isSearchActive) {
                        // The search field lives under the cover: bring it up, clear of the keyboard.
                        dragScope.launch {
                            listState.animateScrollToItem(0, with(density) { 280.dp.roundToPx() })
                        }
                    } else {
                        searchQuery = ""
                    }
                },
            )
            Box {
                DetailBarButton(
                    icon = Icons.Filled.MoreVert,
                    description = "Opciones de la playlist",
                    scrolled = showScrolledHeader,
                    onClick = { overflowMenuOpen = true },
                )
                DropdownMenu(
                    expanded = overflowMenuOpen,
                    onDismissRequest = { overflowMenuOpen = false },
                    shape = RoundedCornerShape(10.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    DropdownMenuItem(
                        text = { Text(if (playlist.isPinned) "Dejar de fijar" else "Fijar arriba") },
                        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                        onClick = {
                            viewModel.togglePinned(playlistId)
                            overflowMenuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Descargar todas las canciones") },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.downloadPlaylist(playlistId)
                            overflowMenuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Cancelar las descargas") },
                        leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.cancelPlaylistDownloads(playlistId)
                            overflowMenuOpen = false
                        },
                    )
                    if (playlist.isYouTubeOnly) {
                        DropdownMenuItem(
                            text = { Text("Guardar una copia local") },
                            leadingIcon = { Icon(Icons.Filled.BookmarkAdd, contentDescription = null) },
                            onClick = {
                                viewModel.makeLocal(playlistId)
                                overflowMenuOpen = false
                            },
                        )
                    } else {
                        if (playlist.mode != LIKED_SONGS_MODE) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.regeneratingId == playlistId) "Regenerando…"
                                        else "Regenerar la playlist",
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                enabled = state.regeneratingId == null,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.regenerate(playlistId) { newId ->
                                        onOpenPlaylist?.invoke(newId)
                                    }
                                    overflowMenuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Cambiar la portada") },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            onClick = {
                                coverEditorOpen = true
                                overflowMenuOpen = false
                            },
                        )
                        if (playlist.mode != LIKED_SONGS_MODE) {
                            DropdownMenuItem(
                                text = { Text("Cambiar el nombre") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    viewModel.requestRename(playlistId)
                                    overflowMenuOpen = false
                                },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Exportar o compartir") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = {
                            viewModel.openExportSheet(playlistId)
                            overflowMenuOpen = false
                        },
                    )
                    if (!playlist.isYouTubeOnly) {
                        val isSyncedToYt = syncedPlaylistIds == null || playlistId in (syncedPlaylistIds ?: emptySet())
                        DropdownMenuItem(
                            text = { Text(if (isSyncedToYt) "Sincronizada con YouTube Music" else "Sincronizar con YouTube Music") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.CloudSync,
                                    contentDescription = null,
                                    tint = if (isSyncedToYt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleYtSync(playlistId)
                                overflowMenuOpen = false
                            },
                        )
                    }
                    if (!playlist.isYouTubeOnly) {
                        DropdownMenuItem(
                            text = { Text("Eliminar la playlist", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                viewModel.requestDelete(playlistId)
                                overflowMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.regeneratingId == playlistId,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = FloatingNavDefaults.contentBottomPadding() + 12.dp),
        ) {
            Surface(
                shape = ExpressivePillShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.spotiaisexs.app.ui.common.ExpressiveInlineLoadingIndicator(
                        size = 20.dp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        "Montando una playlist nueva…",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        // Toasts
        state.toastMessage?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(3000)
                viewModel.dismissToast()
            }
            Surface(
                shape = ExpressivePillShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = FloatingNavDefaults.contentBottomPadding() + 12.dp),
            ) {
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }

    // Cover Editor Dialog
    if (coverEditorOpen) {
        AlertDialog(
            onDismissRequest = { coverEditorOpen = false },
            title = { Text("Portada de la playlist") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PlaylistCover(playlist = playlist, modifier = Modifier.size(140.dp), cornerRadius = 8.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (playlist.customCoverUri.isNullOrBlank()) {
                            "La portada automática es la de la primera canción que tenga carátula."
                        } else {
                            "Esta playlist usa la imagen que elegiste."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coverPickerPending = true
                        coverEditorOpen = false
                        coverPicker.launch(arrayOf("image/*"))
                    },
                ) {
                    Text(if (playlist.customCoverUri.isNullOrBlank()) "Elegir imagen" else "Cambiar imagen")
                }
            },
            dismissButton = {
                Row {
                    if (!playlist.customCoverUri.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                viewModel.setCustomCover(playlistId, null)
                                coverEditorOpen = false
                            },
                        ) { Text("Usar la automática") }
                    }
                    TextButton(onClick = { coverEditorOpen = false }) { Text("Hecho") }
                }
            },
        )
    }

    // Delete confirmation dialog
    if (state.deleteConfirmForPlaylistId != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirm,
            title = { Text("¿Eliminar la playlist?") },
            text = { Text("\"${playlist.title}\" se eliminará para siempre.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmDelete()
                    onBack()
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDeleteConfirm) { Text("Cancelar") } },
        )
    }

    // Rename dialog
    state.renamePlaylistId?.let { id ->
        var title by remember(id) { mutableStateOf(playlist.title) }
        AlertDialog(
            onDismissRequest = viewModel::dismissRename,
            title = { Text("Cambiar el nombre") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nombre de la playlist") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renamePlaylist(title) }, enabled = title.isNotBlank()) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissRename) { Text("Cancelar") } },
        )
    }

    // Export bottom sheet
    state.exportSheetForPlaylistId?.let { id ->
        ExportBottomSheet(
            onDismiss = viewModel::dismissExportSheet,
            onSaveCsv = { viewModel.exportSave(id, ExportFormat.CSV) },
            onSaveM3u = { viewModel.exportSave(id, ExportFormat.M3U) },
            onShareCsv = { viewModel.exportShare(id, ExportFormat.CSV) },
            onShareM3u = { viewModel.exportShare(id, ExportFormat.M3U) },
        )
    }

    // Track Context Menu
    menuTarget?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.name, track.artist, track.url),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
            playbackSourceLabel = playlist.title,
            onDismiss = { menuTarget = null },
            onRemoveFromPlaylist = {
                val realIndex = playlist.tracks.indexOfFirst {
                    (it.url.isNotBlank() && it.url == track.url) ||
                        (it.name.equals(track.name, ignoreCase = true) && it.artist.equals(track.artist, ignoreCase = true))
                }
                if (realIndex >= 0) {
                    viewModel.removeTrack(playlistId, realIndex)
                }
            },
            onDeleteScrobble = { name, artist -> viewModel.deleteScrobble(name, artist) },
            onRefreshArtwork = { viewModel.refreshArtwork(track.name, track.artist) },
        )
    }
}
