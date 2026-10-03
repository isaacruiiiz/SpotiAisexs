package com.spotiaisexs.app.data.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the Addon HTTP protocol.
 *
 * Endpoints:
 *  - GET {baseUrl}/manifest.json
 *  - GET {baseUrl}/search?q=...&quality=...&atmos=...
 *  - GET {baseUrl}/stream/{id}?quality=...&atmos=...
 */

@Serializable
data class AddonManifest(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("resources") val resources: List<String> = emptyList(),
    @SerialName("root") val root: String = "",
) {
    fun declares(resource: String): Boolean =
        resources.any { it.equals(resource, ignoreCase = true) }

    val isPlayable: Boolean
        get() = resources.isEmpty() || declares("search") || declares("stream")

    val displayName: String
        get() = name.ifBlank { id }
}

@Serializable
data class AddonSearchResponse(
    @SerialName("tracks") val tracks: List<AddonTrack> = emptyList(),
)

@Serializable
data class AddonTrack(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("artist") val artist: String = "",
    @SerialName("album") val album: String = "",
    @SerialName("duration") val duration: Double = 0.0,
    @SerialName("format") val format: String = "",
    @SerialName("audioQuality") val audioQuality: String = "",
    @SerialName("atmos") val atmos: Boolean = false,
    @SerialName("audioModes") val audioModes: List<String> = emptyList(),
    @SerialName("artworkURL") val artworkURL: String? = null,
)

@Serializable
data class AddonStream(
    @SerialName("url") val url: String = "",
    @SerialName("dataUrl") val dataUrl: String? = null,
    @SerialName("format") val format: String = "dash",
    @SerialName("codec") val codec: String = "flac",
    @SerialName("quality") val quality: String = "",
    @SerialName("sampleRate") val sampleRate: Double = 44100.0,
    /**
     * Source bit depth reported by the addon, or null when it reported none.
     * Deliberately not defaulted to 16: an omitted field means the addon never
     * measured a depth, and a fabricated 16 was believed at 44.1/48kHz (where
     * nothing contradicts it) and surfaced as a false "16/44.1kHz" pill.
     */
    @SerialName("bitDepth") val bitDepth: Int? = null,
    @SerialName("bitrate") val bitrate: Int? = null,
    @SerialName("manifest") val manifest: String = "dash",
    @SerialName("manifestXml") val manifestXml: String? = null,
    @SerialName("audioMode") val audioMode: String? = null,
    @SerialName("encrypted") val encrypted: Boolean = false,
)

sealed interface AddonHealth {
    data class Ok(val info: String?) : AddonHealth
    data class Unreachable(val reason: String) : AddonHealth
    data class Rejected(val reason: String) : AddonHealth
}
