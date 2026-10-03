package com.spotiaisexs.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.spotiaisexs.app.MainActivity
import com.spotiaisexs.app.R
import com.spotiaisexs.app.data.local.ThemePreferences
import com.spotiaisexs.app.ui.theme.Md3SchemeBuilder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Home-screen "Now Playing" widget — visually modeled on the platform
 * lock-screen media card (art + title/artist + a Pause pill + a round
 * skip button), rebuilt in SpotiAisexs's own Material 3 Expressive language.
 *
 * Speed/reliability notes (this is the thing that was slow before):
 *  - Reads its OWN state exactly once per update, directly in
 *    [provideGlance] via [getAppWidgetState] — everything the composable
 *    needs is passed down as plain parameters, instead of also calling
 *    Glance's `currentState()` inside the content (that was reading the
 *    same Preferences twice per update).
 *  - Album art comes from [WidgetArtCache] (in memory — the exact bitmap
 *    [com.spotiaisexs.app.service.MediaScrobbleListenerService] already had
 *    in hand) whenever the cached track matches; the on-disk PNG copy is
 *    only ever a cold-start fallback, not something decoded on every
 *    update. See [WidgetUpdater] for where the heavy work (art + Palette)
 *    is skipped entirely for a plain pause/resume on the same track.
 *
 * Two other notes:
 *  - Dynamic per-song color: the whole scheme is re-derived from the
 *    CURRENT track's artwork (see [WidgetArtAccent]), using a darker
 *    container tone than the app's own in-app pastel container (see
 *    [Md3SchemeBuilder.buildWidgetScheme]) to match the platform's own
 *    dark lock-screen media card rather than a lighter tonal card.
 *  - Title/artist/the "Pause"/"Play" label are rendered as bitmaps with
 *    the app's real bundled Google Sans Flex font (see
 *    [WidgetTextRenderer]) rather than Glance's `Text()` — RemoteViews has
 *    no supported way to set a custom Typeface on a native TextView.
 */
class NowPlayingWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    // Exact (not the default Single) so LocalSize reflects the widget's
    // REAL current on-screen size — the fixed 155dp text-width cap used
    // to stay the same no matter how wide the user resized the widget,
    // truncating text even when there was visibly empty room. On API<31
    // this degrades gracefully to the declared minimum size, same as
    // before. See NowPlayingWidgetContent for where LocalSize is used.
    override val sizeMode = androidx.glance.appwidget.SizeMode.Exact

    object Keys {
        val trackKey = stringPreferencesKey("np_track_key")
        val title = stringPreferencesKey("np_title")
        val artist = stringPreferencesKey("np_artist")
        val artPath = stringPreferencesKey("np_art_path")
        val artAccentHex = stringPreferencesKey("np_art_accent_hex")
        val isPlaying = booleanPreferencesKey("np_is_playing")
        val hasSession = booleanPreferencesKey("np_has_session")
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun themePreferences(): ThemePreferences
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val appAccentHex = runCatching { entryPoint.themePreferences().prefs.first().accentColor }
            .getOrDefault("#E03030")

        // Single state read for this whole update — see class doc.
        val state = runCatching { getAppWidgetState<Preferences>(context, id) }.getOrNull()
        val hasSession = state?.get(Keys.hasSession) ?: false
        val title = state?.get(Keys.title)?.takeIf { hasSession } ?: "Not playing"
        val artist = state?.get(Keys.artist)?.takeIf { hasSession } ?: "Open a tracked music app"
        val isPlaying = state?.get(Keys.isPlaying) ?: false
        val artPath = state?.get(Keys.artPath)
        val trackKey = state?.get(Keys.trackKey)
        val artAccentHex = state?.get(Keys.artAccentHex)

        val scheme = Md3SchemeBuilder.buildWidgetScheme(artAccentHex ?: appAccentHex)
        val colors = ColorProviders(light = scheme, dark = scheme)

        provideContent {
            GlanceTheme(colors = colors) {
                NowPlayingWidgetContent(
                    scheme = scheme,
                    hasSession = hasSession,
                    title = title,
                    artist = artist,
                    isPlaying = isPlaying,
                    artPath = artPath,
                    trackKey = trackKey,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingWidgetContent(
    scheme: ColorScheme,
    hasSession: Boolean,
    title: String,
    artist: String,
    isPlaying: Boolean,
    artPath: String?,
    trackKey: String?,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density

    // Actual available width for the title/artist text, based on the
    // widget's REAL current size (see SizeMode.Exact above) rather than a
    // fixed guess — art (88dp) + the row's horizontal padding (14dp*2) +
    // the spacer between art and text (14dp), with a sane floor so a
    // tiny/minimum-size widget doesn't collapse text to nothing.
    val widgetSize = androidx.glance.LocalSize.current
    val textMaxWidthDp = (widgetSize.width.value - 88f - 28f - 14f).coerceAtLeast(70f)

    // In-memory cache first (zero disk I/O — the normal, hot path); the
    // file is only ever touched right after a process restart, before the
    // scrobbler service has reconnected and repopulated the cache.
    val artBitmap: Bitmap? = remember(trackKey, artPath) {
        val cached = WidgetArtCache.bitmap
        if (cached != null && WidgetArtCache.trackKey == trackKey) {
            cached
        } else {
            artPath?.let { path -> File(path).takeIf { it.exists() } }?.let { BitmapFactory.decodeFile(it.path) }
        }
    }

    val onContainerArgb = remember(scheme) { scheme.onPrimaryContainer.toArgb() }
    val onPrimaryArgb = remember(scheme) { scheme.onPrimary.toArgb() }

    // Rendered once per (text, color, available width) change — see
    // WidgetTextRenderer for why this has to be a bitmap rather than a
    // styled Text().
    val titleBitmap = remember(title, onContainerArgb, textMaxWidthDp) {
        WidgetTextRenderer.render(
            context, title, onContainerArgb,
            sizeSp = 17f, weight = 760f, width = 112f, round = 34f, maxWidthDp = textMaxWidthDp,
        )
    }
    val artistBitmap = remember(artist, onContainerArgb, textMaxWidthDp) {
        WidgetTextRenderer.render(
            context, artist, onContainerArgb,
            sizeSp = 13.5f, weight = 560f, width = 106f, round = 22f, maxWidthDp = textMaxWidthDp,
        )
    }
    val playLabelBitmap = remember(isPlaying, onPrimaryArgb) {
        WidgetTextRenderer.render(
            context, if (isPlaying) "Pause" else "Play", onPrimaryArgb,
            sizeSp = 14.5f, weight = 760f, width = 114f, round = 34f, maxWidthDp = 60f,
        )
    }

    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(28.dp)
            .appWidgetBackground()
            // Tapping the card anywhere other than the two controls below
            // opens the app — the controls' own clickable modifiers (set
            // further down) take the touch first wherever they overlap.
            // This Glance version's actionStartActivity only takes an
            // explicit Intent — no reified `<Activity>` overload — so the
            // intent is built by hand here rather than via a type param.
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.fillMaxWidth(),
        ) {
            // Bigger, more prominent album art — matches the reference
            // lock-screen card's proportions better than a small thumbnail.
            Box(
                modifier = GlanceModifier
                    .size(88.dp)
                    .cornerRadius(24.dp)
                    .background(GlanceTheme.colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (artBitmap != null) {
                    Image(
                        provider = ImageProvider(artBitmap),
                        contentDescription = null,
                        modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Image(
                        provider = ImageProvider(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = GlanceModifier.size(46.dp),
                    )
                }
            }

            Spacer(modifier = GlanceModifier.width(14.dp))

            Column(modifier = GlanceModifier.defaultWeight()) {
                Image(
                    provider = ImageProvider(titleBitmap),
                    contentDescription = title,
                    modifier = GlanceModifier.size(
                        width = (titleBitmap.width / density).dp,
                        height = (titleBitmap.height / density).dp,
                    ),
                )
                Spacer(modifier = GlanceModifier.height(3.dp))
                Image(
                    provider = ImageProvider(artistBitmap),
                    contentDescription = artist,
                    modifier = GlanceModifier.size(
                        width = (artistBitmap.width / density).dp,
                        height = (artistBitmap.height / density).dp,
                    ),
                )
                Spacer(modifier = GlanceModifier.height(11.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayPausePill(isPlaying = isPlaying, enabled = hasSession, labelBitmap = playLabelBitmap, labelDensity = density)
                    Spacer(modifier = GlanceModifier.width(10.dp))
                    SkipButton(enabled = hasSession)
                }
            }
        }
    }
}

@Composable
private fun PlayPausePill(
    isPlaying: Boolean,
    enabled: Boolean,
    labelBitmap: Bitmap,
    labelDensity: Float,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier
            .background(GlanceTheme.colors.primary)
            .cornerRadius(20.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .then(
                if (enabled) GlanceModifier.clickable(actionRunCallback<TogglePlayPauseAction>())
                else GlanceModifier,
            ),
    ) {
        Image(
            provider = ImageProvider(
                if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            ),
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = GlanceModifier.size(18.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Image(
            provider = ImageProvider(labelBitmap),
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = GlanceModifier.size(
                width = (labelBitmap.width / labelDensity).dp,
                height = (labelBitmap.height / labelDensity).dp,
            ),
        )
    }
}

@Composable
private fun SkipButton(enabled: Boolean) {
    Box(
        modifier = GlanceModifier
            .size(36.dp)
            .background(GlanceTheme.colors.primary)
            .cornerRadius(18.dp)
            .then(
                if (enabled) GlanceModifier.clickable(actionRunCallback<SkipNextAction>())
                else GlanceModifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_skip_next),
            contentDescription = "Skip to next",
            modifier = GlanceModifier.size(16.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
        )
    }
}
