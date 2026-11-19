package com.spotiaisexs.app.data.music

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class SongOrVideoTest {
    @Test
    fun recognisesVideoTitles() {
        listOf(
            "drivers license (Official Video)",
            "happier (Lyric Video)",
            "Despacito (Video Oficial)",
            "Antes (Videoclip)",
            "Song (Visualizer)",
            "Dynamite [MV]",
            "Song (Video)",
            "Song - Official Music Video",
        ).forEach { assertWithMessage(it).that(TextMatch.looksLikeVideoTitle(it)).isTrue() }
        listOf("DtMF", "Rosas (Live)", "Video Games", "Movimiento (Remix)", "Song (Official Audio)")
            .forEach { assertWithMessage(it).that(TextMatch.looksLikeVideoTitle(it)).isFalse() }
    }

    @Test
    fun removesTheVideoLabel() {
        assertThat(TextMatch.withoutVideoWords("drivers license (Official Video)")).isEqualTo("drivers license")
        assertThat(TextMatch.withoutVideoWords("happier - Lyric Video")).isEqualTo("happier")
    }

    @Test
    fun theSongBeatsItsVideoWhenMatching() {
        val song = YouTubeMusicTrack(videoId = "a", title = "drivers license", artist = "Olivia Rodrigo")
        val video = YouTubeMusicTrack(videoId = "v", title = "drivers license", artist = "Olivia Rodrigo", isVideo = true)
        assertThat(TextMatch.matchScore(song, "drivers license", "Olivia Rodrigo"))
            .isGreaterThan(TextMatch.matchScore(video, "drivers license", "Olivia Rodrigo"))
    }
}
