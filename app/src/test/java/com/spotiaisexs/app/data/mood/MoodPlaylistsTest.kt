package com.spotiaisexs.app.data.mood

import com.google.common.truth.Truth.assertThat
import com.spotiaisexs.app.data.music.YouTubeMusicTrack
import com.spotiaisexs.app.playback.TrackFacts
import org.junit.Test

class MoodPlaylistsTest {
    private fun song(id: String, title: String = "Song $id") = YouTubeMusicTrack(videoId = id, title = title, artist = "X")

    @Test
    fun decadesGoByTheReleaseYear() {
        assertThat(MoodPlaylists.fits(Mood.ANOS_80, "Take On Me", TrackFacts(year = 1985), emptySet())).isTrue()
        assertThat(MoodPlaylists.fits(Mood.ANOS_80, "Flowers", TrackFacts(year = 2023), emptySet())).isFalse()
        // Without the year nothing is guessed in.
        assertThat(MoodPlaylists.fits(Mood.ANOS_80, "Take On Me", null, emptySet())).isFalse()
    }

    @Test
    fun tempoDecidesGymAndChill() {
        assertThat(MoodPlaylists.fits(Mood.GYM, "Titanium", TrackFacts(bpm = 126), emptySet())).isTrue()
        assertThat(MoodPlaylists.fits(Mood.GYM, "Someone Like You", TrackFacts(bpm = 67), setOf("ballad"))).isFalse()
        assertThat(MoodPlaylists.fits(Mood.CHILL, "Someone Like You", TrackFacts(bpm = 67), setOf("ballad"))).isTrue()
        assertThat(MoodPlaylists.fits(Mood.CHILL, "Titanium", TrackFacts(bpm = 126), setOf("edm"))).isFalse()
    }

    @Test
    fun theLyricsDecideTheLanguageOfThePop() {
        val spanish = LyricSignal(language = 1)
        val english = LyricSignal(language = -1)
        // "drivers license" says nothing by its title; the lyrics do.
        assertThat(MoodPlaylists.fits(Mood.POP_EN, "drivers license", null, setOf("pop"), english)).isTrue()
        assertThat(MoodPlaylists.fits(Mood.POP_ES, "drivers license", null, setOf("pop"), english)).isFalse()
        // Lyrics win over a title in the other language.
        assertThat(MoodPlaylists.fits(Mood.POP_ES, "Love Song", null, setOf("pop"), spanish)).isTrue()
        // Reggaeton is not pop, even in Spanish.
        assertThat(MoodPlaylists.fits(Mood.POP_ES, "Gasolina", null, setOf("reggaeton"), spanish)).isFalse()
    }

    @Test
    fun partyAndMotivationCanComeFromTheLyrics() {
        assertThat(MoodPlaylists.fits(Mood.FIESTA, "Una canción", TrackFacts(bpm = 100), emptySet(), LyricSignal(party = true))).isTrue()
        assertThat(MoodPlaylists.fits(Mood.FIESTA, "Una canción", TrackFacts(bpm = 100), emptySet(), LyricSignal())).isFalse()
        assertThat(MoodPlaylists.fits(Mood.MOTIVACION, "Una canción", null, emptySet(), LyricSignal(motivation = true))).isTrue()
    }

    @Test
    fun twoOfYoursThenOneNewWithoutRepeats() {
        val yours = (1..50).map { song("y$it") }
        val newOnes = (1..50).map { song("n$it") } + song("dup", "Song y1")
        val tracks = MoodPlaylists.interleave(yours, newOnes)
        assertThat(tracks).hasSize(MoodPlaylists.MAX_TRACKS)
        assertThat(tracks.take(6).map { it.videoId }).containsExactly("y1", "y2", "n1", "y3", "y4", "n2").inOrder()
        assertThat(tracks.map { it.videoId }).doesNotContain("dup")
    }

    @Test
    fun idsRoundTrip() {
        Mood.entries.forEach { assertThat(MoodPlaylists.parse(MoodPlaylists.idFor(it))).isEqualTo(it) }
        assertThat(MoodPlaylists.parse("mood:nope")).isNull()
        assertThat(MoodPlaylists.parse("thisis:Quevedo")).isNull()
    }
}
