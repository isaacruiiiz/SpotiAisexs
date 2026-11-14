package com.spotiaisexs.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.spotiaisexs.app.R

/**
 * Design "Dial": Barlow for everything you read, Barlow Condensed for section
 * headers and Instrument Serif for the few big titles ("Tu mezcla", the song
 * in the player). All three are SIL OFL 1.1 (docs/licenses).
 */
val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_semibold, FontWeight.Bold),
)

val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

val InstrumentSerif = FontFamily(Font(R.font.instrument_serif, FontWeight.Normal))

/** Big serif title: "Tu mezcla", the song in the player, "Lo que viene". */
fun heroTitle(size: Int) = TextStyle(
    fontFamily = InstrumentSerif,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = (size * 1.02f).sp,
    letterSpacing = (-0.01f * size).sp,
)

val SpotiAisexsTypography = Typography(
    displayLarge = heroTitle(57),
    displayMedium = heroTitle(46),
    displaySmall = heroTitle(38),
    // Large titles ("Biblioteca", "Buscar").
    headlineLarge = heroTitle(36),
    headlineMedium = heroTitle(30),
    headlineSmall = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp),
    // Section headers ("Emisoras", "Hace poco").
    titleLarge = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)

/**
 * The "Use Application Font" toggle's off-state: plain platform default
 * (FontFamily.Default), same sizes/weights as [SpotiAisexsTypography] so
 * turning the toggle off changes ONLY the typeface, not the whole scale.
 */
val SystemTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)
