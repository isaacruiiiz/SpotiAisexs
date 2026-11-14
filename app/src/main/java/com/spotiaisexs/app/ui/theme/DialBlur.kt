package com.spotiaisexs.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur

/**
 * Smoked-glass blur of the "Dial" design, the same idea as RunList's
 * DsTopBlur / DsBarBlur: whatever scrolls behind a bar is blurred and
 * darkened instead of being covered by an opaque strip. Plain blur only: no
 * lens, no highlight rim, none of the old liquid-glass refraction.
 *
 * It needs Android 12 and a device that is not low on RAM; elsewhere the
 * callers fall back to an almost opaque dark fill, which reads the same.
 */

/** Marks the content that the blurred bars drawn on top of it will show through. */
@Composable
fun Modifier.blurSource(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null || !isDeviceGlassCapable()) return this
    return this.layerBackdropCompat(backdrop)
}

/**
 * Draws [backdrop] blurred inside [shape] and then [tint] over it. Content of
 * the composable is clipped to [shape].
 */
@Composable
fun Modifier.dialBlur(
    backdrop: Backdrop?,
    shape: Shape,
    radius: Dp = 20.dp,
    fallback: Color = Color(0xF2101010),
    tint: DrawScope.() -> Unit,
): Modifier {
    if (backdrop == null || !isDeviceGlassCapable()) return this.clip(shape).background(fallback)
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { blur(radiusPx) },
        highlight = null,
        shadow = null,
        onDrawSurface = tint,
    )
}

/**
 * Progressive blur under the status bar: strongest at the very top and gone a
 * little below the clock, so content fades out as it scrolls under it instead
 * of meeting a hard edge. Built from thin bands of decreasing blur (the
 * approach RunList uses); it never takes touches.
 */
@Composable
fun TopBlurFade(backdrop: Backdrop?, modifier: Modifier = Modifier, extra: Dp = 20.dp) {
    if (backdrop == null || !isDeviceGlassCapable()) return
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val maxRadiusPx = with(LocalDensity.current) { TOP_BLUR_MAX_RADIUS.toPx() }
    Column(modifier.fillMaxWidth().height(statusBar + extra)) {
        repeat(TOP_BLUR_BANDS) { index ->
            // 1 at the top, 0 at the bottom, easing out at the end.
            val strength = 1f - (index + 0.5f) / TOP_BLUR_BANDS
            val radiusPx = maxRadiusPx * strength * strength
            val veil = Color.Black.copy(alpha = TOP_BLUR_VEIL * strength)
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = { blur(radiusPx) },
                        highlight = null,
                        shadow = null,
                        onDrawSurface = { drawRect(veil) },
                    ),
            )
        }
    }
}

private const val TOP_BLUR_BANDS = 10
private val TOP_BLUR_MAX_RADIUS = 22.dp

/** Slight darkening with the blur so the clock and icons stay readable over bright covers. */
private const val TOP_BLUR_VEIL = 0.38f
