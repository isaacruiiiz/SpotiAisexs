package com.spotiaisexs.app.ui.artist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.spotiaisexs.app.data.model.ArtistAlbumItem
import com.spotiaisexs.app.data.model.ArtistPageData
import com.spotiaisexs.app.data.model.ArtistSummaryItem
import com.spotiaisexs.app.playback.PlayableTrack
import com.spotiaisexs.app.ui.common.ArtworkImage
import com.spotiaisexs.app.ui.common.DetailActionRow
import com.spotiaisexs.app.ui.common.DetailBarButton
import com.spotiaisexs.app.ui.common.DetailHero
import com.spotiaisexs.app.ui.common.DetailMeta
import com.spotiaisexs.app.ui.common.DetailPlayButton
import com.spotiaisexs.app.ui.common.DetailSectionHeader
import com.spotiaisexs.app.ui.common.DetailTopBar
import com.spotiaisexs.app.ui.common.DetailTrackRow
import com.spotiaisexs.app.ui.common.DialHaptics
import com.spotiaisexs.app.ui.common.ExpressiveLoadingIndicator
import com.spotiaisexs.app.ui.common.TrackContextMenuSheet
import com.spotiaisexs.app.ui.common.TrackMenuCapabilities
import com.spotiaisexs.app.ui.common.TrackMenuTarget
import com.spotiaisexs.app.ui.common.adaptiveContentWidth
import com.spotiaisexs.app.ui.common.detailTitleStyle
import com.spotiaisexs.app.ui.common.safeDrawingBottomPadding
import com.spotiaisexs.app.ui.genres.GenreExplorer
import com.spotiaisexs.app.ui.player.LocalMiniPlayerScrollClearance
import com.spotiaisexs.app.ui.player.LocalMusicPlayer
import com.spotiaisexs.app.ui.radio.DialOutlinedButton
import com.spotiaisexs.app.util.ArtistHelper

/** How many of the artist's songs show before "Ver todas". */
private const val POPULAR_PREVIEW = 5

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    browseId: String? = null,
    onBack: () -> Unit,
    onOpenAlbum: (title: String, artist: String, browseId: String?) -> Unit = { _, _, _ -> },
    onOpenArtist: (name: String, browseId: String?) -> Unit = { _, _ -> },
    onOpenThisIs: (playlistId: String) -> Unit = {},
    viewModel: ArtistViewModel = hiltViewModel(),
    genreBridge: ArtistDetailGenreBridge = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val musicPlayer = LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()

    // System back / predictive-back gesture is owned by the wrapping
    // PredictiveBackScreen in NavGraph (single handler per screen) — the
    // toolbar button below still pops directly via onBack.

    LaunchedEffect(artistName, browseId) {
        viewModel.loadArtist(artistName, browseId)
    }

    var selectedTrackMenu by remember { mutableStateOf<PlayableTrack?>(null) }
    var showAllSongs by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val heroEnd = with(LocalDensity.current) { 240.dp.toPx() }
    val showScrolledHeader by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > heroEnd }
    }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val success = uiState as? ArtistUiState.Success

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val state = uiState) {
            is ArtistUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingIndicator(message = "Abriendo a $artistName…")
                }
            }
            is ArtistUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    DialOutlinedButton(
                        "Reintentar",
                        onClick = { viewModel.loadArtist(artistName, browseId) },
                        accent = true,
                    )
                }
            }
            is ArtistUiState.Success -> {
                val data = state.data
                val displayName = ArtistHelper.primaryArtist(data.name)
                val isArtistSource = playbackState.current != null && playbackState.sourceLabel == data.name
                val isArtistPlaying = isArtistSource && playbackState.isPlaying

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .adaptiveContentWidth(maxWidth = 860.dp)
                        .align(Alignment.TopCenter),
                ) {
                    // 1. The photo from edge to edge, with the name on the fade.
                    item(key = "artist_hero") {
                        DetailHero(
                            height = 360.dp + statusBar,
                            scrollOffset = {
                                if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 0f
                            },
                            image = { ArtistPhoto(data) },
                        ) {
                            Text(
                                displayName,
                                style = detailTitleStyle(displayName),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    (data.subscribers ?: data.monthlyListeners)?.takeIf(String::isNotBlank)?.let { audience ->
                        item(key = "artist_audience") { DetailMeta(audienceLabel(audience)) }
                    }

                    item(key = "artist_actions") {
                        DetailActionRow {
                            DialOutlinedButton(
                                text = "Emisora",
                                onClick = viewModel::startArtistMix,
                                accent = true,
                                leading = { Icon(Icons.Filled.Radio, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            )
                            if (data.topSongs.isNotEmpty()) {
                                DialOutlinedButton(
                                    text = "Aleatorio",
                                    onClick = viewModel::playShuffle,
                                    leading = { Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            DetailPlayButton(
                                playing = isArtistPlaying,
                                description = if (isArtistPlaying) "Pausar" else "Reproducir a $displayName",
                                enabled = data.topSongs.isNotEmpty(),
                                onClick = { if (isArtistSource) musicPlayer.togglePlayPause() else viewModel.playAll() },
                            )
                        }
                    }

                    // 2. "Esto es {artista}": their hits mixed with what the user plays most of them.
                    if (data.topSongs.isNotEmpty()) {
                        item(key = "artist_this_is") {
                            ThisIsCard(
                                data = data,
                                displayName = displayName,
                                onClick = {
                                    onOpenThisIs(
                                        com.spotiaisexs.app.data.thisis.ThisIsPlaylists.idFor(
                                            data.name,
                                            data.browseId.takeIf { it.isNotBlank() } ?: browseId,
                                        ),
                                    )
                                },
                            )
                        }
                    }

                    // 3. Their songs, numbered.
                    if (data.topSongs.isNotEmpty()) {
                        val visibleSongs = if (showAllSongs) data.topSongs else data.topSongs.take(POPULAR_PREVIEW)

                        item(key = "top_songs_header") { DetailSectionHeader("Populares") }

                        itemsIndexed(
                            visibleSongs,
                            key = { index, track -> "${track.videoId ?: track.title}_$index" },
                            contentType = { _, _ -> "artist_top_song" },
                        ) { index, track ->
                            val isPlayingThis = playbackState.current?.title.equals(track.title, ignoreCase = true) &&
                                playbackState.current?.artist.equals(track.artist, ignoreCase = true)
                            DetailTrackRow(
                                title = track.title,
                                subtitle = track.album?.takeIf(String::isNotBlank)
                                    ?: ArtistHelper.primaryArtist(track.artist).ifBlank { displayName },
                                artworkUrl = track.artworkUrl,
                                artworkArtist = ArtistHelper.primaryArtist(track.artist),
                                playing = isPlayingThis,
                                index = index + 1,
                                onClick = { viewModel.playAll(startIndex = index) },
                                onLongClick = { selectedTrackMenu = track },
                                onMenu = { selectedTrackMenu = track },
                                onQueue = { musicPlayer.addToQueue(track) },
                            )
                        }

                        if (data.topSongs.size > POPULAR_PREVIEW) {
                            item(key = "top_songs_toggle_button") {
                                Box(Modifier.padding(start = 20.dp, top = 12.dp)) {
                                    DialOutlinedButton(
                                        text = if (showAllSongs) "Ver menos" else "Ver las ${data.topSongs.size} canciones",
                                        onClick = { showAllSongs = !showAllSongs },
                                    )
                                }
                            }
                        }
                    }

                    // 4. Albums
                    if (data.albums.isNotEmpty()) {
                        item(key = "albums_header") { DetailSectionHeader("Álbumes") }
                        item(key = "albums_row") {
                            ReleaseShelf(data.albums) { album ->
                                onOpenAlbum(album.title, displayName, album.browseId)
                            }
                        }
                    }

                    // 5. Singles & EPs
                    if (data.singles.isNotEmpty()) {
                        item(key = "singles_header") { DetailSectionHeader("Sencillos y EP") }
                        item(key = "singles_row") {
                            ReleaseShelf(data.singles) { single ->
                                onOpenAlbum(single.title, displayName, single.browseId)
                            }
                        }
                    }

                    // 6. Genre tags & bio
                    if (data.tags.isNotEmpty() || !data.bio.isNullOrBlank()) {
                        item(key = "artist_tags_bio_header") { DetailSectionHeader("Acerca de") }
                        item(key = "artist_tags_bio") {
                            Column(
                                Modifier.padding(horizontal = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (data.tags.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        data.tags.take(6).forEach { tag ->
                                            DialOutlinedButton(tag, onClick = { genreBridge.genreExplorer.explore(tag) })
                                        }
                                    }
                                }
                                data.bio?.takeIf(String::isNotBlank)?.let { bio -> ArtistBio(bio) }
                            }
                        }
                    }

                    // 7. Similar artists
                    if (data.similarArtists.isNotEmpty()) {
                        item(key = "similar_artists_header") { DetailSectionHeader("Artistas parecidos") }
                        item(key = "similar_artists_row") {
                            val individualArtists = remember(data.similarArtists) {
                                data.similarArtists.flatMap { item ->
                                    val split = ArtistHelper.splitArtists(item.name)
                                    if (split.size <= 1) listOf(item)
                                    else split.map { singleName ->
                                        item.copy(name = singleName, browseId = "")
                                    }
                                }.distinctBy { it.name.lowercase().trim() }
                            }
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp),
                            ) {
                                items(individualArtists, key = { it.browseId.ifBlank { it.name } }) { artist ->
                                    SimilarArtistCard(
                                        artist = artist,
                                        onClick = { onOpenArtist(ArtistHelper.primaryArtist(artist.name), artist.browseId) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        DetailTopBar(
            title = ArtistHelper.primaryArtist(success?.data?.name ?: artistName),
            // Without a photo behind it the bar has nothing to float on.
            scrolled = showScrolledHeader || success == null,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            if (showScrolledHeader && success?.data?.topSongs?.isNotEmpty() == true) {
                DetailBarButton(
                    icon = Icons.Filled.PlayArrow,
                    description = "Reproducir",
                    scrolled = true,
                    active = true,
                    onClick = { viewModel.playAll() },
                )
            }
        }
    }

    // Context Menu Sheet for Tracks
    selectedTrackMenu?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.title, track.artist, ""),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = false),
            playableTrack = track,
            playbackSourceLabel = artistName,
            onDismiss = { selectedTrackMenu = null },
        )
    }
}

/**
 * YouTube Music gives a bare count ("2,12 M"), Last.fm a count with the word
 * "listeners": say what is being counted, in Spanish.
 */
private fun audienceLabel(raw: String): String {
    val text = raw.trim()
    return when {
        text.endsWith(" listeners") -> text.removeSuffix(" listeners") + " oyentes"
        // More than a magnitude ("M", "mil", "mill."): the text already says what it counts.
        text.count(Char::isLetter) > 4 -> text
        else -> "$text de suscriptores"
    }
}

/** The artist's picture, trying the next candidate when one fails to load. */
@Composable
private fun ArtistPhoto(data: ArtistPageData) {
    val candidates = remember(data) {
        listOfNotNull(data.artworkUrl, data.bannerUrl, data.fallbackArtworkUrl)
            .filter(com.spotiaisexs.app.data.artwork.ArtworkNormalizer::isRealImage).distinct()
    }
    var candidateIndex by remember(data) { mutableStateOf(0) }
    val url = candidates.getOrNull(candidateIndex)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
            onError = { candidateIndex++ },
        )
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(96.dp),
            )
        }
    }
}

/** Entry to "Esto es {artista}": the artist's picture with a strip of the accent, like its cover. */
@Composable
private fun ThisIsCard(data: ArtistPageData, displayName: String, onClick: () -> Unit) {
    val view = LocalView.current
    Surface(
        onClick = {
            DialHaptics.click(view)
            onClick()
        },
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp),
    ) {
        Row(
            Modifier
                .defaultMinSize(minHeight = 68.dp)
                .padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                ArtworkImage(
                    name = data.name,
                    artist = data.name,
                    embeddedUrl = data.artworkUrl ?: data.fallbackArtworkUrl,
                    fallbackIcon = Icons.Filled.Person,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Esto es $displayName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Sus éxitos y lo que más le escuchas tú",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/** The bio, four lines at first. */
@Composable
private fun ArtistBio(bio: String) {
    var expanded by remember(bio) { mutableStateOf(false) }
    Column {
        Text(
            text = bio,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (bio.length > 120) {
            Text(
                text = if (expanded) "Leer menos" else "Leer más",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .defaultMinSize(minHeight = 44.dp)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** A row of releases that scrolls sideways. */
@Composable
private fun ReleaseShelf(releases: List<ArtistAlbumItem>, onOpen: (ArtistAlbumItem) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        items(releases, key = { it.browseId.ifBlank { it.title } }) { release ->
            ArtistAlbumCard(item = release, onClick = { onOpen(release) })
        }
    }
}

/** "Album" / "Single" as YouTube Music names them, in Spanish, followed by the year. */
private fun releaseLabel(item: ArtistAlbumItem): String {
    val raw = item.type?.trim().orEmpty()
    val type = when (raw.lowercase()) {
        "", "album" -> "Álbum"
        "single" -> "Sencillo"
        else -> raw
    }
    return listOfNotNull(type, item.year?.takeIf(String::isNotBlank)).joinToString(", ")
}

@Composable
private fun ArtistAlbumCard(
    item: ArtistAlbumItem,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        ArtworkImage(
            name = item.title,
            artist = "",
            embeddedUrl = item.artworkUrl,
            fallbackIcon = Icons.Filled.Album,
            modifier = Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = releaseLabel(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SimilarArtistCard(
    artist: ArtistSummaryItem,
    onClick: () -> Unit,
) {
    val name = ArtistHelper.primaryArtist(artist.name)
    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (!artist.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artist.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class ArtistDetailGenreBridge @javax.inject.Inject constructor(
    val genreExplorer: GenreExplorer,
) : androidx.lifecycle.ViewModel()
