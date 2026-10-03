package com.spotiaisexs.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "WidgetUpdater"
private const val ART_FILE_NAME = "widget_now_playing_art.png"

/**
 * The only place that writes to [NowPlayingWidget]'s persisted state.
 * Called from [com.spotiaisexs.app.service.MediaScrobbleListenerService].
 *
 * Ordering: the service calls [nextSeq] SYNCHRONOUSLY (not from inside a
 * coroutine) at the exact moment it decides to publish, before launching
 * the actual (possibly slower) publish work — so sequence numbers reflect
 * true call order even though the work that applies them runs
 * concurrently and can finish out of order. A publish that finishes after
 * a newer one has already applied just skips writing (see [claimIfLatest])
 * instead of overwriting the widget with stale info.
 *
 * This replaces an earlier version that used external coroutine
 * cancellation (cancelling whatever publish job was still in flight
 * before starting a new one) for the same ordering guarantee — that
 * turned out to be the actual cause of the widget going completely
 * unresponsive: onStateChanged fires often enough (buffering → playing
 * transitions, etc.) that a new publish could arrive and cancel the
 * previous one before ITS OWN DataStore write had finished committing,
 * repeatedly, so no update ever actually completed. Every publish call
 * below now always runs to completion; staleness is resolved by simply
 * not applying a superseded result, never by interrupting one mid-write.
 *
 * Split into a heavy path and a light path on purpose — this is the main
 * "make it fast" fix from earlier: the old single `publish()` re-did
 * art-file-write + Palette extraction on EVERY playback-state transition
 * (which fire far more often than the track actually changing):
 *  - [publishTrack] only runs when the track key actually changed (the
 *    service gates the call, since it already tracks its own key). Art
 *    goes straight into [WidgetArtCache] (in-memory — zero disk I/O on
 *    the visible path) and triggers exactly ONE repaint. Writing the same
 *    art to disk for cold-start durability happens AFTER that repaint and
 *    doesn't trigger a second one.
 *  - [publishPlaybackState] just flips one boolean and repaints — no art
 *    or color work at all.
 */
object WidgetUpdater {

    private val requestSeq = AtomicLong(0)
    @Volatile private var latestAppliedSeq = 0L

    /** Called synchronously by the service right when it decides to
     *  publish — captures this call's position in the real order of
     *  events, before the (async, possibly slower) work below runs. */
    fun nextSeq(): Long = requestSeq.incrementAndGet()

    /** True if [seq] is still the newest thing anyone's tried to publish —
     *  i.e. nothing newer has already been applied. Claims it as applied
     *  if so, atomically, so two out-of-order finishers can't both think
     *  they're the latest. */
    private fun claimIfLatest(seq: Long): Boolean {
        synchronized(this) {
            if (seq < latestAppliedSeq) return false
            latestAppliedSeq = seq
            return true
        }
    }

    suspend fun publishTrack(
        seq: Long,
        context: Context,
        trackKey: String,
        title: String,
        artist: String,
        art: Bitmap?,
        isPlaying: Boolean,
    ) {
        // Heavy work (Palette, in-memory art cache) happens regardless —
        // cheap enough, and WidgetArtCache itself is just "last one wins"
        // which is fine even if this particular publish turns out stale,
        // since a genuinely newer track publish will overwrite it anyway.
        if (art != null) WidgetArtCache.set(trackKey, art)
        val artAccentHex = art?.let { bitmap -> WidgetArtAccent.extractHex(bitmap) }

        if (!claimIfLatest(seq)) return // a newer publish already landed — don't paint over it

        updateAllAndRepaint(context) { prefs ->
            prefs[NowPlayingWidget.Keys.trackKey] = trackKey
            prefs[NowPlayingWidget.Keys.title] = title
            prefs[NowPlayingWidget.Keys.artist] = artist
            prefs[NowPlayingWidget.Keys.isPlaying] = isPlaying
            prefs[NowPlayingWidget.Keys.hasSession] = true
            if (artAccentHex != null) prefs[NowPlayingWidget.Keys.artAccentHex] = artAccentHex
        }

        // Cold-start durability only (widget rebuilt after a process death,
        // before the scrobbler reconnects) — the widget is already showing
        // this exact art via WidgetArtCache, so this doesn't need to (and
        // deliberately doesn't) trigger another repaint.
        if (art != null) {
            val artPath = writeArt(context, art)
            if (artPath != null) updateStateOnly(context) { prefs -> prefs[NowPlayingWidget.Keys.artPath] = artPath }
        }
    }

    /** Cheap path for a play/pause/buffering transition on a track already
     *  published — no art, no Palette, just the one flag. */
    suspend fun publishPlaybackState(seq: Long, context: Context, isPlaying: Boolean) {
        if (!claimIfLatest(seq)) return
        updateAllAndRepaint(context) { prefs ->
            prefs[NowPlayingWidget.Keys.isPlaying] = isPlaying
            prefs[NowPlayingWidget.Keys.hasSession] = true
        }
    }

    /** Called when the last watched session goes away — widget falls back
     *  to its empty "nothing playing" state rather than showing stale info. */
    suspend fun clear(seq: Long, context: Context) {
        if (!claimIfLatest(seq)) return
        WidgetArtCache.clear()
        updateAllAndRepaint(context) { prefs ->
            prefs[NowPlayingWidget.Keys.hasSession] = false
            prefs[NowPlayingWidget.Keys.isPlaying] = false
            prefs.remove(NowPlayingWidget.Keys.artAccentHex)
            prefs.remove(NowPlayingWidget.Keys.trackKey)
        }
    }

    private suspend fun updateAllAndRepaint(
        context: Context,
        edit: (MutablePreferences) -> Unit,
    ) {
        runCatching {
            val ids = updateStateOnly(context, edit)
            if (ids.isNotEmpty()) NowPlayingWidget().updateAll(context)
        }.onFailure { Log.w(TAG, "widget update failed", it) }
    }

    /** Persists a state change WITHOUT forcing a recomposition — for
     *  writes (like the art-file cold-start path above) that don't need
     *  to be reflected on screen immediately. Returns the glance IDs
     *  written, so callers can tell whether there was even a widget
     *  instance to update. */
    private suspend fun updateStateOnly(
        context: Context,
        edit: (MutablePreferences) -> Unit,
    ) = runCatching {
        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(NowPlayingWidget::class.java)
        ids.forEach { id -> updateAppWidgetState(context, id) { prefs -> edit(prefs) } }
        ids
    }.onFailure { Log.w(TAG, "widget state write failed", it) }.getOrDefault(emptyList())

    private fun writeArt(context: Context, bitmap: Bitmap): String? = runCatching {
        val file = File(context.filesDir, ART_FILE_NAME)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, out) }
        file.absolutePath
    }.onFailure { Log.w(TAG, "failed to cache widget art", it) }.getOrNull()
}
