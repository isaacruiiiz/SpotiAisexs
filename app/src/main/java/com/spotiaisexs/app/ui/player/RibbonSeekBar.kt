package com.spotiaisexs.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotiaisexs.app.playback.PlaybackProgressState
import com.spotiaisexs.app.ui.common.DialHaptics
import com.spotiaisexs.app.ui.theme.BarlowCondensed
import kotlinx.coroutines.flow.StateFlow

/**
 * Seek bar "Cinta" of the Dial design: a thick ribbon whose played part is
 * filled with the accent and carries the elapsed time written inside it; the
 * remaining time sits at the right end. Tap anywhere to jump there, or drag
 * anywhere along it: while dragging the fill turns white and shows the time
 * you would land on, and the seek only happens when the finger lifts.
 */
@Composable
internal fun RibbonSeekBar(
    progressState: StateFlow<PlaybackProgressState>,
    trackKey: String?,
    fallbackDurationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by progressState.collectAsStateWithLifecycle()
    val durationMs = (if (progress.durationMs > 0L) progress.durationMs else fallbackDurationMs).coerceAtLeast(0L)
    val livePositionMs = progress.positionMs.coerceIn(0L, durationMs)
    val seek by rememberUpdatedState(onSeek)
    val currentDuration by rememberUpdatedState(durationMs)
    val view = LocalView.current

    // Fraction under the finger; null = not touching, the bar follows playback.
    var dragFraction by remember(trackKey) { mutableStateOf<Float?>(null) }
    val liveFraction = if (durationMs > 0L) (livePositionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
    val fraction = dragFraction ?: liveFraction
    val shownMs = dragFraction?.let { (it * durationMs).toLong() } ?: livePositionMs
    val dragging = dragFraction != null

    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = if (dragging) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
    val inkOnFill = if (dragging) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onPrimary
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val elapsedStyle = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        fontFeatureSettings = "tnum",
    )
    val remainingStyle = elapsedStyle.copy(fontWeight = FontWeight.SemiBold)
    val elapsedText = formatTime(shownMs)
    val remainingText = "-" + formatTime((durationMs - shownMs).coerceAtLeast(0L))

    fun fractionAt(x: Float, width: Int): Float = if (width <= 0) 0f else (x / width).coerceIn(0f, 1f)

    // A light notch every ten seconds of song passed under the finger.
    fun moveTo(newFraction: Float) {
        val before = dragFraction
        if (before != null && currentDuration > 0L) {
            val beforeStep = (before * currentDuration / SEEK_NOTCH_MS).toLong()
            val afterStep = (newFraction * currentDuration / SEEK_NOTCH_MS).toLong()
            if (beforeStep != afterStep) DialHaptics.tick(view)
        }
        dragFraction = newFraction
    }

    fun commit() {
        val target = dragFraction?.let { (it * currentDuration).toLong().coerceIn(0L, currentDuration) }
        dragFraction = null
        if (target != null && currentDuration > 0L) {
            DialHaptics.click(view)
            seek(target)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            // 44 dp touch target around the 36 dp ribbon.
            .height(44.dp)
            .pointerInput(trackKey) {
                detectTapGestures { offset ->
                    if (currentDuration <= 0L) return@detectTapGestures
                    dragFraction = fractionAt(offset.x, size.width)
                    commit()
                }
            }
            .pointerInput(trackKey) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (currentDuration > 0L) {
                            DialHaptics.step(view)
                            dragFraction = fractionAt(offset.x, size.width)
                        }
                    },
                    onDragEnd = { commit() },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    if (currentDuration <= 0L) return@detectHorizontalDragGestures
                    change.consume()
                    moveTo(fractionAt(change.position.x, size.width))
                }
            }
            .semantics {
                contentDescription = "Posición en la canción"
                stateDescription = "$elapsedText de ${formatTime(durationMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { value ->
                    if (currentDuration <= 0L) return@setProgress false
                    seek((value.coerceIn(0f, 1f) * currentDuration).toLong())
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val corner = CornerRadius(8.dp.toPx())
            val ribbon = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, corner)) }
            val fillWidth = size.width * fraction
            val padding = 10.dp.toPx()

            val elapsed = measurer.measure(elapsedText, elapsedStyle)
            val remaining = measurer.measure(remainingText, remainingStyle)
            val textTop = (size.height - elapsed.size.height) / 2f
            // The elapsed time rides inside the fill once it fits, otherwise just after it.
            val fitsInside = fillWidth >= elapsed.size.width + padding * 2f
            val elapsedX = if (fitsInside) fillWidth - padding - elapsed.size.width else fillWidth + padding
            val elapsedEnd = elapsedX + elapsed.size.width
            val remainingX = size.width - padding - remaining.size.width

            clipPath(ribbon) {
                drawRect(track, size = size)
                if (fillWidth > 0f) drawRect(fill, size = Size(fillWidth, size.height))
            }
            // Hide the remaining time when the elapsed label would run into it.
            if (elapsedEnd + padding < remainingX) {
                drawText(remaining, color = muted, topLeft = Offset(remainingX, (size.height - remaining.size.height) / 2f))
            }
            drawText(
                elapsed,
                color = if (fitsInside) inkOnFill else ink,
                topLeft = Offset(elapsedX.coerceAtMost(size.width - elapsed.size.width - padding), textTop),
            )
        }
    }
}

private const val SEEK_NOTCH_MS = 10_000L
