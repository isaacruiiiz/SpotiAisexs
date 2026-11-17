package com.spotiaisexs.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotiaisexs.app.ui.theme.BarlowCondensed
import com.spotiaisexs.app.ui.theme.heroTitle

/*
 * Pieces of the "Portada" detail screens (playlist, artist, "Esto es"): the
 * picture fills the top of the screen and fades into the black background,
 * the big serif title sits on that fade, and the songs are plain rows
 * separated by a hairline instead of cards.
 */

/** "1 canción", "22 canciones". */
fun songCountLabel(count: Int): String = if (count == 1) "1 canción" else "$count canciones"

/** The big serif title shrinks for long names so it never takes more than three lines. */
fun detailTitleStyle(text: String): TextStyle = heroTitle(
    when {
        text.length <= 12 -> 60
        text.length <= 24 -> 48
        else -> 40
    },
)

/**
 * Picture from edge to edge, fading to the background colour at the bottom.
 * [content] is laid out at the bottom-left, on top of the fade. [scrollOffset]
 * is how far the hero has scrolled away, in pixels: the picture moves a bit
 * slower than the list.
 */
@Composable
fun DetailHero(
    height: Dp,
    modifier: Modifier = Modifier,
    scrollOffset: () -> Float = { 0f },
    image: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = scrollOffset() * 0.4f },
            content = image,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.38f),
                        0.26f to Color.Transparent,
                        0.48f to Color.Black.copy(alpha = 0.2f),
                        0.78f to background.copy(alpha = 0.82f),
                        1f to background,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            content = content,
        )
    }
}

/**
 * Bar over the hero: round buttons on a dark scrim while the picture is
 * showing; once the list has scrolled it turns solid and shows [title].
 */
@Composable
fun DetailTopBar(
    title: String,
    scrolled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val barColor by animateColorAsState(
        targetValue = if (scrolled) MaterialTheme.colorScheme.background.copy(alpha = 0.94f) else Color.Transparent,
        animationSpec = tween(220),
        label = "detailBar",
    )
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .fillMaxWidth()
            .background(barColor)
            .drawBehind {
                if (scrolled) drawLine(hairline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DetailBarButton(Icons.AutoMirrored.Filled.ArrowBack, "Volver", scrolled, onBack)
            Box(Modifier.weight(1f)) {
                // Fully qualified: inside the Box the Row's own AnimatedVisibility is out of reach.
                androidx.compose.animation.AnimatedVisibility(visible = scrolled, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}

/** Round 44 dp button of the detail bar. [active] paints it with the accent (search open, for example). */
@Composable
fun DetailBarButton(
    icon: ImageVector,
    description: String,
    scrolled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val view = LocalView.current
    val fill = when {
        active -> MaterialTheme.colorScheme.primary
        scrolled -> Color.Transparent
        else -> Color.Black.copy(alpha = 0.5f)
    }
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Button) {
                DialHaptics.click(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The one filled button of the screen: round, accent, at the right end of the action row. */
@Composable
fun DetailPlayButton(
    playing: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    Surface(
        onClick = {
            DialHaptics.confirm(view)
            onClick()
        },
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = description,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** Line of facts under the hero ("Playlist, 100 canciones"). */
@Composable
fun DetailMeta(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
    )
}

/** Outlined buttons on the left, the play button pushed to the right. */
@Composable
fun DetailActionRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun DetailSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Small outlined word in the accent colour: marks a song of "Esto es" that comes from the user's listening. */
@Composable
fun DetailTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * A song as a plain row: hairline on top, cover, title and one line under it.
 * The playing song takes the accent. Sliding the row to the right sends the
 * song to the queue when [onQueue] is given, like in the search.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailTrackRow(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkArtist: String = subtitle,
    index: Int? = null,
    onLongClick: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val queueAction by rememberUpdatedState<(() -> Unit)?>(
        onQueue?.let { action ->
            {
                DialHaptics.confirm(view)
                action()
                android.widget.Toast.makeText(context, "Añadida a la cola", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
    )
    val queueThreshold = with(LocalDensity.current) { 84.dp.toPx() }
    var swipeX by remember(title, subtitle) { mutableFloatStateOf(0f) }
    val shownSwipeX by animateFloatAsState(swipeX, label = "detailQueueSwipe")
    val hairline = MaterialTheme.colorScheme.outlineVariant

    Box(modifier.fillMaxWidth()) {
        if (onQueue != null && shownSwipeX > 1f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "A la cola",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = shownSwipeX }
                .background(MaterialTheme.colorScheme.background)
                .then(
                    if (onQueue != null) {
                        Modifier.pointerInput(title, subtitle) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (swipeX >= queueThreshold) queueAction?.invoke()
                                    swipeX = 0f
                                },
                                onDragCancel = { swipeX = 0f },
                            ) { change, amount ->
                                change.consume()
                                val next = (swipeX + amount).coerceIn(0f, queueThreshold * 1.4f)
                                // A notch when the row passes the point where letting go queues it.
                                if ((next >= queueThreshold) != (swipeX >= queueThreshold)) DialHaptics.step(view)
                                swipeX = next
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    onClick = {
                        DialHaptics.click(view)
                        onClick()
                    },
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 20.dp)
                .drawBehind { drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Text(
                    "$index",
                    style = TextStyle(
                        fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.width(26.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            ArtworkImage(
                name = title,
                artist = artworkArtist,
                embeddedUrl = artworkUrl,
                fallbackIcon = Icons.Filled.MusicNote,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playing) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = "Sonando",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke(this)
            if (onMenu != null) {
                Box(
                    Modifier
                        // The dots sit close to the edge; the 44 dp touch area hangs into the margin.
                        .offset(x = 10.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) {
                            DialHaptics.click(view)
                            onMenu()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Más opciones de $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
