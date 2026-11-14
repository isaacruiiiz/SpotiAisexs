package com.spotiaisexs.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
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
 * of meeting a hard edge. It never takes touches.
 *
 * Each layer is blurred once and then faded out with a continuous gradient
 * mask (DstIn inside the layer, which is drawn offscreen), so there are no
 * steps between bands. Two layers stacked, a soft one reaching the bottom and
 * a strong one only near the top, make the blur itself grow smoothly upwards
 * instead of a single blurred copy simply cross-fading over the sharp content.
 */
@Composable
fun TopBlurFade(backdrop: Backdrop?, modifier: Modifier = Modifier, extra: Dp = 28.dp) {
    if (backdrop == null || !isDeviceGlassCapable()) return
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current
    val softPx = with(density) { TOP_BLUR_SOFT.toPx() }
    val strongPx = with(density) { TOP_BLUR_STRONG.toPx() }
    Box(modifier.fillMaxWidth().height(statusBar + extra)) {
        FadedBlurLayer(backdrop, softPx, reach = 1f, veil = TOP_BLUR_VEIL)
        FadedBlurLayer(backdrop, strongPx, reach = 0.62f, veil = 0f)
    }
}

/** One blurred copy of [backdrop], fully visible at the top and fading out by [reach] of the height. */
@Composable
private fun FadedBlurLayer(backdrop: Backdrop, radiusPx: Float, reach: Float, veil: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = { blur(radiusPx) },
                highlight = null,
                shadow = null,
                onDrawSurface = {
                    // Keep the blur only where the mask is opaque: a smooth ease-out curve.
                    drawRect(
                        Brush.verticalGradient(
                            colorStops = fadeStops(reach, Color.Black),
                            startY = 0f,
                            endY = size.height,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                    if (veil > 0f) {
                        // Slight darkening, so the clock and icons stay readable over bright covers.
                        drawRect(
                            Brush.verticalGradient(
                                colorStops = fadeStops(reach, Color.Black.copy(alpha = veil)),
                                startY = 0f,
                                endY = size.height,
                            ),
                        )
                    }
                },
            ),
    )
}

/**
 * Gradient stops for [color] going from full strength at the top to nothing at
 * [reach] (fraction of the height), following 1 - smoothstep so neither end
 * has a visible edge.
 */
private fun fadeStops(reach: Float, color: Color): Array<Pair<Float, Color>> {
    val steps = 16
    return Array(steps + 1) { index ->
        val t = index / steps.toFloat()
        val eased = 1f - t * t * (3f - 2f * t)
        (t * reach) to color.copy(alpha = color.alpha * eased)
    }.let { stops ->
        if (reach < 1f) stops + (1f to color.copy(alpha = 0f)) else stops
    }
}

private val TOP_BLUR_SOFT = 10.dp
private val TOP_BLUR_STRONG = 26.dp

/** Darkening that goes with the blur at the very top. */
private const val TOP_BLUR_VEIL = 0.42f
