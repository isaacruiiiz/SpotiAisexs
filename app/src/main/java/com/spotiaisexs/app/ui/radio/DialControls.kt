package com.spotiaisexs.app.ui.radio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotiaisexs.app.playback.RadioReason
import com.spotiaisexs.app.playback.RadioReasonKind
import com.spotiaisexs.app.playback.RadioTuning
import com.spotiaisexs.app.ui.common.DialHaptics
import kotlin.math.roundToInt

/** The dial gives one light notch every this many points of its 0-100 scale. */
private const val DIAL_NOTCH = 4

/** Corner radius of the "Dial" design: small and square-ish, like hardware buttons. */
val DialCorner = RoundedCornerShape(8.dp)

/**
 * The signature control of the design: a radio tuner scale with an orange
 * needle. Left is "Cerca" (same artist), right is "Lejos" (surprise me).
 * Tap anywhere or drag to move it.
 */
@Composable
fun RadioDial(
    distance: Int,
    onDistanceChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tall: Boolean = false,
    startLabel: String? = "Cerca",
    endLabel: String? = "Lejos",
    scaleLabels: List<String> = emptyList(),
) {
    val accent = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.outline
    val onChange by rememberUpdatedState(onDistanceChange)
    val view = LocalView.current
    val currentDistance by rememberUpdatedState(distance)
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = (dragging ?: distance).coerceIn(0, 100)
    val scaleHeight = if (tall) 26.dp else 18.dp

    // The dial clicks under the finger like a real tuner: a light notch every few
    // points of the scale and a firmer one when the needle enters another zone.
    fun moveNeedle(to: Int) {
        val from = dragging ?: currentDistance
        if (to == from) return
        if (RadioTuning(distance = to).label != RadioTuning(distance = from).label) {
            DialHaptics.step(view)
        } else if (to / DIAL_NOTCH != from / DIAL_NOTCH) {
            DialHaptics.tick(view)
        }
        dragging = to
    }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                // 44 dp tall touch target around a much thinner scale.
                .height(44.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        DialHaptics.step(view)
                        onChange(((offset.x / size.width) * 100f).roundToInt().coerceIn(0, 100))
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            moveNeedle(((offset.x / size.width) * 100f).roundToInt().coerceIn(0, 100))
                        },
                        onDragEnd = {
                            dragging?.let { onChange(it) }
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    ) { change, _ ->
                        change.consume()
                        moveNeedle(((change.position.x / size.width) * 100f).roundToInt().coerceIn(0, 100))
                    }
                }
                .semantics {
                    contentDescription = "Dial de la radio, de cerca a lejos"
                    stateDescription = RadioTuning(distance = shown).label
                    progressBarRangeInfo = ProgressBarRangeInfo(shown / 100f, 0f..1f)
                    setProgress { value ->
                        onChange((value * 100f).roundToInt().coerceIn(0, 100))
                        true
                    }
                },
        ) {
            val scalePx = scaleHeight.toPx()
            val top = (size.height - scalePx) / 2f
            val step = 10.dp.toPx()
            val stroke = 1.dp.toPx()
            var x = stroke / 2f
            while (x <= size.width) {
                drawLine(tick, Offset(x, top), Offset(x, top + scalePx), strokeWidth = stroke)
                x += step
            }
            val needleWidth = 3.dp.toPx()
            val needleX = (size.width * shown / 100f).coerceIn(needleWidth / 2f, size.width - needleWidth / 2f)
            val overshoot = 5.dp.toPx()
            drawRoundRect(
                color = accent,
                topLeft = Offset(needleX - needleWidth / 2f, top - overshoot),
                size = Size(needleWidth, scalePx + overshoot * 2f),
                cornerRadius = CornerRadius(needleWidth / 2f),
            )
        }
        if (scaleLabels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                scaleLabels.forEach { label ->
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (startLabel != null && endLabel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(startLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(endLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A flat, bordered button of the design. [accent] paints border and text orange. */
@Composable
fun DialOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val view = LocalView.current
    Surface(
        onClick = {
            DialHaptics.click(view)
            onClick()
        },
        shape = DialCorner,
        color = Color.Transparent,
        contentColor = color,
        border = BorderStroke(1.dp, if (accent) color else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 1f)),
        modifier = modifier.defaultMinSize(minHeight = 44.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Suena porque…" with the two buttons that teach the radio. */
@Composable
fun RadioReasonStrip(
    because: String,
    onMore: () -> Unit,
    onLess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = DialCorner,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Suena porque",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    because,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DialOutlinedButton("Menos así", onLess)
            DialOutlinedButton("Más así", onMore, accent = true)
        }
    }
}

/** A switch drawn as a chip: filled orange when on, bordered when off. */
@Composable
fun DialSwitchChip(text: String, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    Surface(
        onClick = {
            // Turning a switch on feels like a confirmation, turning it off like a plain click.
            if (selected) DialHaptics.click(view) else DialHaptics.confirm(view)
            onToggle()
        },
        shape = DialCorner,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .semantics { stateDescription = if (selected) "Activado" else "Desactivado" },
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

/** The four radio switches. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadioSwitches(
    tuning: RadioTuning,
    onChange: ((RadioTuning) -> RadioTuning) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DialSwitchChip("Solo en español", tuning.onlySpanish, { onChange { it.copy(onlySpanish = !it.onlySpanish) } })
        DialSwitchChip("Sin lentas", tuning.noSlow, { onChange { it.copy(noSlow = !it.noSlow) } })
        DialSwitchChip("Sin repetir artista", tuning.noRepeatArtist, { onChange { it.copy(noRepeatArtist = !it.noRepeatArtist) } })
        DialSwitchChip("Solo de esta década", tuning.thisDecade, { onChange { it.copy(thisDecade = !it.thisDecade) } })
    }
}

/** Reason tag at the end of a queue row; "Nueva" stands out, the rest stay quiet. */
@Composable
fun RadioReasonTag(reason: RadioReason, modifier: Modifier = Modifier) {
    if (reason.kind == RadioReasonKind.NEW) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = modifier,
        ) {
            Text(
                reason.tag,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    } else {
        Text(
            reason.tag,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

/** Bottom row of the player: Jam, Letra, Lo que viene. */
@Composable
fun PlayerQuickActions(
    onJam: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    jamLive: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DialOutlinedButton(
            text = if (jamLive) "Jam en directo" else "Jam",
            onClick = onJam,
            accent = jamLive,
            modifier = Modifier.weight(1f),
            leading = { Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DialOutlinedButton("Letra", onLyrics, Modifier.weight(1f))
        DialOutlinedButton("Lo que viene", onQueue, Modifier.weight(1f))
    }
}
