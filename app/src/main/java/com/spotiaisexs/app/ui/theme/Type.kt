@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.spotiaisexs.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.spotiaisexs.app.R

/**
 * Geist (SIL OFL 1.1, docs/licenses/Geist-OFL.txt), bundled as a variable
 * font (res/font/geist.ttf, wght 100–900). Design "Cristal, color de
 * portada": large titles are Bold with tight tracking, section titles
 * SemiBold, body Regular and labels SemiBold — quiet type so the artwork
 * color carries the personality.
 */
private fun geist(weight: Int): FontFamily = FontFamily(
    Font(
        R.font.geist,
        weight = when {
            weight >= 700 -> FontWeight.Bold
            weight >= 600 -> FontWeight.SemiBold
            weight >= 500 -> FontWeight.Medium
            else -> FontWeight.Normal
        },
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    ),
)

val SpotiAisexsTypography = Typography(
    displayLarge = TextStyle(fontFamily = geist(700), fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 62.sp, letterSpacing = (-2.0).sp),
    displayMedium = TextStyle(fontFamily = geist(700), fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 50.sp, letterSpacing = (-1.6).sp),
    displaySmall = TextStyle(fontFamily = geist(700), fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-1.2).sp),
    // Large titles ("Inicio", "Biblioteca").
    headlineLarge = TextStyle(fontFamily = geist(700), fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1.0).sp),
    headlineMedium = TextStyle(fontFamily = geist(700), fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontFamily = geist(600), fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
    // Section headers ("Escuchado hace poco").
    titleLarge = TextStyle(fontFamily = geist(600), fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = geist(600), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontFamily = geist(600), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = geist(400), fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = geist(400), fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = geist(400), fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = geist(600), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = geist(600), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = geist(600), fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.1.sp),
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
