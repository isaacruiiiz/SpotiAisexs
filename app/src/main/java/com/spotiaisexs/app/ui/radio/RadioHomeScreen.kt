package com.spotiaisexs.app.ui.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotiaisexs.app.data.music.YouTubeMusicTrack
import com.spotiaisexs.app.data.thisis.ThisIsPlaylists
import com.spotiaisexs.app.data.playlist.PlaylistRepository
import com.spotiaisexs.app.data.repository.SongPlayStatsRepository
import com.spotiaisexs.app.playback.MusicPlayer
import com.spotiaisexs.app.playback.PlayableTrack
import com.spotiaisexs.app.playback.RadioStation
import com.spotiaisexs.app.playback.SavedStation
import com.spotiaisexs.app.playback.RadioTuning
import com.spotiaisexs.app.playback.toPlayableTrack
import com.spotiaisexs.app.ui.common.ArtworkImage
import com.spotiaisexs.app.ui.common.DialHaptics
import com.spotiaisexs.app.ui.feed.ArtistAlbumNavBridgeFeed
import com.spotiaisexs.app.ui.feed.FeedViewModel
import com.spotiaisexs.app.ui.navigation.ArtistAlbumNavigator
import com.spotiaisexs.app.ui.shell.FloatingNavDefaults
import com.spotiaisexs.app.ui.theme.BarlowCondensed
import com.spotiaisexs.app.ui.theme.heroTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class RadioHomeViewModel @Inject constructor(
    private val musicPlayer: MusicPlayer,
    private val songPlayStats: SongPlayStatsRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    val tuning: StateFlow<RadioTuning> = musicPlayer.radioTuning

    val savedStations: StateFlow<List<SavedStation>> = musicPlayer.savedStations

    fun playSaved(station: SavedStation) = musicPlayer.playSavedStation(station)

    fun deleteSaved(station: SavedStation) = musicPlayer.deleteSavedStation(station.id)

    private val _thisIs = MutableStateFlow<List<ThisIsEntry>>(emptyList())
    /** The artists the user listens to most lately, each with an "Esto es…" playlist. */
    val thisIs: StateFlow<List<ThisIsEntry>> = _thisIs.asStateFlow()

    private val _recent = MutableStateFlow<List<PlayableTrack>>(emptyList())
    val recent: StateFlow<List<PlayableTrack>> = _recent.asStateFlow()

    fun refreshRecent() {
        viewModelScope.launch(Dispatchers.IO) {
            _recent.value = runCatching { songPlayStats.recentlyPlayed(RECENT_COUNT) }.getOrDefault(emptyList())
                .filter { it.title.isNotBlank() }
                .map { PlayableTrack(title = it.title, artist = it.artist, artworkUrl = it.artworkUrl, videoId = it.videoId) }
            _thisIs.value = runCatching { songPlayStats.topArtistsSince(days = 90, limit = THIS_IS_COUNT) }
                .getOrDefault(emptyList())
                .map { (artist, topSong) -> ThisIsEntry(artist, topSong.artworkUrl) }
        }
    }

    fun playStation(station: RadioStation) {
        if (station != RadioStation.ME_GUSTA) {
            musicPlayer.playStation(station)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val liked = runCatching { playlistRepository.getLikedSongs()?.tracks }.getOrNull().orEmpty()
            if (liked.isEmpty()) return@launch
            val queue = liked.map { it.toPlayableTrack() }.shuffled()
            withContext(Dispatchers.Main) { musicPlayer.playQueue(queue, 0, station.sourceLabel) }
        }
    }

    fun play(track: PlayableTrack, sourceLabel: String) = musicPlayer.play(track, sourceLabel = sourceLabel)

    fun addToQueue(track: PlayableTrack) = musicPlayer.addToQueue(track)

    fun setDistance(distance: Int) = musicPlayer.setRadioDistance(distance)

    private companion object {
        const val RECENT_COUNT = 9
        const val THIS_IS_COUNT = 6
    }
}

/** One "Esto es {artista}" tile; [coverUrl] is the cover of the user's most played song of theirs. */
data class ThisIsEntry(val artist: String, val coverUrl: String?)

private fun YouTubeMusicTrack.asPlayable() = PlayableTrack(
    title = title,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
    videoId = videoId,
    durationMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L),
)

/**
 * Home of the "Dial" design: one big play ("Tu mezcla") with the dial, the
 * stations, what was played recently and, further down, Spotify-style
 * recommendations of songs, playlists and artists.
 */
@Composable
fun RadioHomeScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenFeedPlaylist: (String) -> Unit,
    viewModel: RadioHomeViewModel = hiltViewModel(),
    feedViewModel: FeedViewModel = hiltViewModel(),
    artistAlbumNavigator: ArtistAlbumNavigator = hiltViewModel<ArtistAlbumNavBridgeFeed>().navigator,
) {
    val tuning by viewModel.tuning.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val thisIs by viewModel.thisIs.collectAsStateWithLifecycle()
    val savedStations by viewModel.savedStations.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val feedState by feedViewModel.uiState.collectAsStateWithLifecycle()
    val feed = feedState.feedData

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, feedViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                feedViewModel.onVisible()
                viewModel.refreshRecent()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val songs = (feed.quickPicks.ifEmpty { feed.becauseYouListenTo?.items.orEmpty() }.ifEmpty { feed.freshFinds })
        .distinctBy { it.videoId }
        .take(5)
    val playlists = (feed.ytSuggestedPlaylists + feed.newReleases).distinctBy { it.id }.take(8)
    val artists = feed.topArtists.distinctBy { it.name.lowercase() }.take(10)

    LazyColumn(
        // No status-bar padding on the list itself: its content scrolls up under the
        // clock, where the progressive blur fades it out.
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            bottom = FloatingNavDefaults.contentBottomPadding() + 12.dp,
        ),
    ) {
        item(key = "header") {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EqualizerLogo(Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "SpotiAisexs",
                        fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onOpenStats) {
                        Icon(Icons.Filled.BarChart, contentDescription = "Tus estadísticas", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ajustes", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = onOpenSearch,
                    shape = DialCorner,
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .fillMaxWidth()
                        .height(46.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, DialCorner),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Canción, artista o álbum", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        item(key = "mix") {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(RadioStation.TU_MEZCLA.title, style = heroTitle(54))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                RadioStation.TU_MEZCLA.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Surface(
                            onClick = {
                                DialHaptics.confirm(view)
                                viewModel.playStation(RadioStation.TU_MEZCLA)
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(68.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Reproducir Tu mezcla", modifier = Modifier.size(34.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    RadioDial(
                        distance = tuning.distance,
                        onDistanceChange = viewModel::setDistance,
                        startLabel = "Cerca: lo de siempre",
                        endLabel = "Lejos: sorpréndeme",
                    )
                }
            }
        }

        item(key = "stations") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                SectionTitle("Emisoras")
                Spacer(Modifier.height(8.dp))
                val cells = buildList {
                    RadioStation.entries.filter { it != RadioStation.TU_MEZCLA }.forEach { station ->
                        add(StationCell(station.title, station.subtitle, onClick = { viewModel.playStation(station) }))
                    }
                    savedStations.forEach { saved ->
                        add(
                            StationCell(
                                title = saved.name,
                                subtitle = "Guardada, mantén pulsado para quitarla",
                                onClick = { viewModel.playSaved(saved) },
                                onLongClick = {
                                    DialHaptics.reject(view)
                                    viewModel.deleteSaved(saved)
                                    android.widget.Toast.makeText(context, "Emisora quitada", android.widget.Toast.LENGTH_SHORT).show()
                                },
                            ),
                        )
                    }
                    add(StationCell("Nueva emisora", "Desde un artista o canción", onClick = onOpenSearch, dashed = true))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cells.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { cell -> StationButton(cell, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item(key = "recent") {
                Column(Modifier.padding(top = 18.dp)) {
                    SectionTitle("Hace poco", Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(recent, key = { "${it.title}|${it.artist}" }) { track ->
                            CoverTile(
                                title = track.title,
                                subtitle = track.artist,
                                artworkUrl = track.artworkUrl,
                                size = 104,
                                onClick = { viewModel.play(track, "Hace poco") },
                            )
                        }
                    }
                }
            }
        }

        if (songs.isNotEmpty()) {
            item(key = "songs") {
                Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp)) {
                    SectionTitle("Canciones para ti")
                    Spacer(Modifier.height(4.dp))
                    songs.forEach { song ->
                        SongRow(
                            title = song.title,
                            artist = song.artist,
                            artworkUrl = song.artworkUrl,
                            onClick = { viewModel.play(song.asPlayable(), "Canciones para ti") },
                            onQueue = { viewModel.addToQueue(song.asPlayable()) },
                        )
                    }
                }
            }
        }

        if (thisIs.isNotEmpty() || feed.mixes.isNotEmpty() || playlists.isNotEmpty()) {
            item(key = "playlists") {
                Column(Modifier.padding(top = 20.dp)) {
                    SectionTitle("Playlists para ti", Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(thisIs, key = { "thisis:${it.artist}" }) { entry ->
                            // Prefer a photo of the artist when the feed has one.
                            val known = feed.topArtists.firstOrNull { it.name.equals(entry.artist, ignoreCase = true) }
                            ThisIsTile(
                                artist = entry.artist,
                                imageUrl = known?.artworkUrl ?: entry.coverUrl,
                                onClick = { onOpenFeedPlaylist(ThisIsPlaylists.idFor(entry.artist, known?.browseId)) },
                            )
                        }
                        items(feed.mixes.distinctBy { it.title }.take(6), key = { "mix:${it.title}" }) { mix ->
                            CoverTile(
                                title = mix.title,
                                subtitle = "Empieza con ${mix.seed.title}",
                                artworkUrl = mix.seed.artworkUrl,
                                size = 132,
                                onClick = { feedViewModel.playMix(mix) },
                            )
                        }
                        items(playlists, key = { "pl:${it.id}" }) { playlist ->
                            CoverTile(
                                title = playlist.title,
                                subtitle = playlist.author ?: playlist.trackCountText.orEmpty(),
                                artworkUrl = playlist.artworkUrl,
                                size = 132,
                                onClick = { onOpenFeedPlaylist(playlist.id) },
                            )
                        }
                    }
                }
            }
        }

        if (artists.isNotEmpty()) {
            item(key = "artists") {
                Column(Modifier.padding(top = 20.dp)) {
                    SectionTitle("Artistas para ti", Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(artists, key = { "artist:${it.name}" }) { artist ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(92.dp)
                                    .clickable { artistAlbumNavigator.openArtist(artist.name, artist.browseId) },
                            ) {
                                ArtworkImage(
                                    name = artist.name,
                                    artist = artist.name,
                                    embeddedUrl = artist.artworkUrl,
                                    fallbackIcon = Icons.Filled.Person,
                                    modifier = Modifier.size(92.dp).clip(CircleShape),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    artist.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (feedState.isLoading && songs.isEmpty() && playlists.isEmpty() && artists.isEmpty()) {
            item(key = "loading") {
                Text(
                    "Buscando recomendaciones para ti…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), modifier = modifier)
}

/** The logo: five equalizer bars in the accent colour. */
@Composable
private fun EqualizerLogo(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = "SpotiAisexs" }) {
        val heights = listOf(0.36f, 0.66f, 1f, 0.6f, 0.3f)
        val slot = size.width / heights.size
        val bar = slot * 0.62f
        heights.forEachIndexed { index, fraction ->
            val barHeight = size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(slot * index + (slot - bar) / 2f, (size.height - barHeight) / 2f),
                size = Size(bar, barHeight),
                cornerRadius = CornerRadius(bar / 2f),
            )
        }
    }
}

private class StationCell(
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    val dashed: Boolean = false,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationButton(cell: StationCell, modifier: Modifier = Modifier) {
    val title = cell.title
    val subtitle = cell.subtitle
    val dashed = cell.dashed
    val outline = if (dashed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant
    val view = LocalView.current
    Surface(
        shape = DialCorner,
        color = Color.Transparent,
        modifier = modifier
            .height(60.dp)
            .clip(DialCorner)
            .combinedClickable(
                onClick = {
                    DialHaptics.click(view)
                    cell.onClick()
                },
                onLongClick = cell.onLongClick,
            )
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(
                        width = stroke,
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null,
                    ),
                )
            },
    ) {
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CoverTile(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    size: Int,
    onClick: () -> Unit,
) {
    Column(Modifier.width(size.dp).clickable(onClick = onClick)) {
        ArtworkImage(
            name = title,
            artist = subtitle,
            embeddedUrl = artworkUrl,
            fallbackIcon = Icons.Filled.MusicNote,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Cover of an "Esto es" playlist: the artist's picture, darkened at the bottom,
 * with the name in the big serif and an accent strip, so the series is
 * recognisable at a glance among the other playlists.
 */
@Composable
private fun ThisIsTile(artist: String, imageUrl: String?, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            ArtworkImage(
                name = artist,
                artist = artist,
                embeddedUrl = imageUrl,
                fallbackIcon = Icons.Filled.Person,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.35f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .width(4.dp)
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 8.dp, bottom = 8.dp)) {
                Text(
                    "Esto es",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Text(
                    artist,
                    style = heroTitle(24),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Esto es $artist",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Sus éxitos y lo tuyo",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SongRow(
    title: String,
    artist: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .drawBehind {
                drawLine(Color(0xFF262626), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            name = title,
            artist = artist,
            embeddedUrl = artworkUrl,
            fallbackIcon = Icons.Filled.MusicNote,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val view = LocalView.current
        IconButton(
            onClick = {
                DialHaptics.confirm(view)
                onQueue()
            },
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Añadir $title a la cola", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
