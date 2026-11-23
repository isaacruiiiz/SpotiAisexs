package com.spotiaisexs.app.data.mood

import com.google.common.truth.Truth.assertThat
import com.spotiaisexs.app.data.local.db.SongPlayStatsEntity
import com.spotiaisexs.app.data.music.YouTubeMusicTrack
import com.spotiaisexs.app.data.music.YouTubePlaylistSummary
import com.spotiaisexs.app.playback.TrackFacts
import io.mockk.mockk
import org.junit.Test

class MoodPlaylistsTest {
    private val builder = MoodPlaylists(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private fun mine(title: String, id: String) =
        SongPlayStatsEntity(trackKey = title.lowercase(), title = title, artist = "Varios", videoId = id, totalPlayTimeMs = 600_000, playCount = 3)

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
    fun popByLanguage() {
        assertThat(MoodPlaylists.fits(Mood.POP_ES, "Te regalo", null, setOf("pop", "latin"))).isTrue()
        assertThat(MoodPlaylists.fits(Mood.POP_EN, "Te regalo", null, setOf("pop", "latin"))).isFalse()
        assertThat(MoodPlaylists.fits(Mood.POP_EN, "drivers license", null, setOf("pop"))).isFalse() // can't tell the language
        assertThat(MoodPlaylists.fits(Mood.POP_EN, "Love you like a love song", null, setOf("pop"))).isTrue()
    }

    @Test
    fun mixesTheUserSongsWithTheCatalogWithoutRepeats() {
        val catalog = (1..80).map { YouTubeMusicTrack(videoId = "c$it", title = "Hit $it", artist = "X") }
        val user = listOf(mine("Mine 1", "m1"), mine("Hit 2 (Official Video)", "v2"))
        val tracks = builder.mix(catalog, user)
        assertThat(tracks).hasSize(MoodPlaylists.MAX_TRACKS)
        assertThat(tracks.take(4).map { it.videoId }).containsExactly("m1", "c1", "c2", "c3").inOrder()
    }

    @Test
    fun youTubeMusicOwnPlaylistsComeFirst() {
        val found = listOf(
            YouTubePlaylistSummary(id = "PLfan", title = "Gym fan list", author = "Someone"),
            YouTubePlaylistSummary(id = "RDCLAKgym", title = "Workout Hits", author = "YouTube Music"),
            YouTubePlaylistSummary(id = "RDCLAKgym", title = "Workout Hits", author = "YouTube Music"),
        )
        assertThat(MoodPlaylists.pickPlaylists(found).map { it.id }).containsExactly("RDCLAKgym", "PLfan").inOrder()
    }

    @Test
    fun idsRoundTrip() {
        Mood.entries.forEach { assertThat(MoodPlaylists.parse(MoodPlaylists.idFor(it))).isEqualTo(it) }
        assertThat(MoodPlaylists.parse("mood:nope")).isNull()
        assertThat(MoodPlaylists.parse("thisis:Quevedo")).isNull()
    }
}
