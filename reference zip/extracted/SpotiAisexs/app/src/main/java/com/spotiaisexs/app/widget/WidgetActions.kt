package com.spotiaisexs.app.widget

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.spotiaisexs.app.service.MediaScrobbleListenerService

/**
 * Tap actions for the widget's Play/Pause and Skip buttons — call real
 * MediaController transport controls.
 *
 * These do NOT rely solely on [ActiveMediaSessionHolder] having already
 * been set by a currently-alive [MediaScrobbleListenerService] instance —
 * that object only gets populated once the service has actually run and
 * bound a session in THIS process lifetime, which isn't guaranteed (OEM
 * battery optimizers, or the app simply hasn't been opened since reboot).
 * [resolveController] falls back to asking the system directly for the
 * active session via [MediaSessionManager], which only needs the
 * Notification Listener permission to already be granted — not the
 * service to be "warm" — so the buttons work the same whether or not the
 * app has been opened recently.
 */
private fun resolveController(context: Context): MediaController? {
    val cached = ActiveMediaSessionHolder.controller
    if (cached != null && runCatching { cached.playbackState }.isSuccess) return cached

    val fresh = runCatching {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val component = ComponentName(context, MediaScrobbleListenerService::class.java)
        manager?.getActiveSessions(component)
            ?.firstOrNull { it.playbackState != null }
    }.getOrNull()

    if (fresh != null) ActiveMediaSessionHolder.controller = fresh
    return fresh
}

class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val controller = resolveController(context) ?: return
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING

        // Optimistic UI: flip the widget's own Pause/Play state immediately
        // rather than waiting on the round trip to the other app's
        // MediaSession and back — this is what actually makes the tap feel
        // instant. If the target app rejects the change for some reason,
        // its own callback (handled in MediaScrobbleListenerService) will
        // correct the widget back to the real state shortly after.
        runCatching { WidgetUpdater.publishPlaybackState(WidgetUpdater.nextSeq(), context, !playing) }

        runCatching { if (playing) controller.transportControls.pause() else controller.transportControls.play() }
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val controller = resolveController(context) ?: return
        runCatching { controller.transportControls.skipToNext() }
    }
}
