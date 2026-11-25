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
        assertThat(MoodPlaylists.fits(Mood.GYM, "Titanium", TrackFacts(bpm = 126, genres = listOf("dance")), emptySet())).isTrue()
        // Fast alone is not enough for the gym: it has to be music made to push.
        assertThat(MoodPlaylists.fits(Mood.GYM, "Titanium", TrackFacts(bpm = 126), emptySet())).isFalse()
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
    fun reggaetonIsNeverPopEvenWhenACatalogueSaysSo() {
        val spanish = LyricSignal(language = 1)
        // Deezer files a lot of reggaeton as pop or latin.
        assertThat(MoodPlaylists.fits(Mood.POP_ES, "Provenza", TrackFacts(genres = listOf("pop")), setOf("pop"), spanish, artist = "KAROL G")).isFalse()
        // Nothing known about the artist, but the lyrics talk like reggaeton.
        assertThat(MoodPlaylists.fits(Mood.POP_ES, "Una canción", null, emptySet(), spanish.copy(urban = true), artist = "Alguien")).isFalse()
        // A single filed as pop by an artist who usually makes reggaeton.
        assertThat(
            MoodPlaylists.fits(Mood.POP_ES, "Otra", TrackFacts(genres = listOf("pop")), emptySet(), spanish, artistGenres = setOf("reggaeton"), artist = "Alguien"),
        ).isFalse()
        // Real Spanish pop stays.
        assertThat(MoodPlaylists.fits(Mood.POP_ES, "Chica de ayer", TrackFacts(genres = listOf("pop")), emptySet(), spanish, artist = "Nacha Pop")).isTrue()
    }

    @Test
    fun gymIsOnlyMusicThatPushes() {
        // Rumba and 80s pop are not gym music, however fast.
        assertThat(MoodPlaylists.fits(Mood.GYM, "Pastillas para dormir", TrackFacts(bpm = 130, genres = listOf("pop")), setOf("rumba", "spanish"), artist = "Estopa")).isFalse()
        assertThat(MoodPlaylists.fits(Mood.GYM, "Chica de ayer", TrackFacts(bpm = 128, genres = listOf("pop", "rock")), emptySet(), artist = "Nacha Pop")).isFalse()
        // Trap and reggaeton are.
        assertThat(MoodPlaylists.fits(Mood.GYM, "Mbappe", TrackFacts(genres = listOf("rap/hip hop")), emptySet(), artist = "Eladio Carrión")).isTrue()
        assertThat(MoodPlaylists.fits(Mood.GYM, "Algo", TrackFacts(bpm = 96, genres = listOf("latin music")), emptySet(), artist = "Wisin & Yandel")).isTrue()
    }

    @Test
    fun fiestaNeedsPartyMusicNotJustLatin() {
        assertThat(MoodPlaylists.fits(Mood.FIESTA, "La flaca", TrackFacts(bpm = 100, genres = listOf("pop", "latin music")), setOf("latin", "rock"), LyricSignal(language = 1), artist = "Jarabe De Palo")).isFalse()
        assertThat(MoodPlaylists.fits(Mood.FIESTA, "Provenza", TrackFacts(bpm = 111, genres = listOf("latin music")), emptySet(), artist = "KAROL G")).isTrue()
        assertThat(MoodPlaylists.fits(Mood.FIESTA, "Titanium", TrackFacts(bpm = 126, genres = listOf("dance")), emptySet())).isTrue()
    }

    @Test
    fun anArtistsUsualGenresComeFromMostOfTheirSongs() {
        val profile = MoodPlaylists.artistGenres(
            listOf(
                "karol g" to listOf("latin music", "reggaeton"),
                "karol g" to listOf("reggaeton"),
                "karol g" to listOf("pop"),
                "nacha pop" to listOf("pop"),
                "nadie" to emptyList(),
            ),
        )
        assertThat(profile["karol g"]).containsExactly("reggaeton")
        assertThat(profile["nacha pop"]).containsExactly("pop")
        assertThat(profile).doesNotContainKey("nadie")
    }

    @Test
    fun radiosStartFromAllOverTheList() {
        assertThat(MoodPlaylists.spread((0 until 100).toList(), 4)).containsExactly(0, 25, 50, 75).inOrder()
        assertThat(MoodPlaylists.spread(listOf(1, 2), 4)).containsExactly(1, 2).inOrder()
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
