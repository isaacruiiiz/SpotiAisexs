package com.spotiaisexs.app.data.thisis

import com.google.common.truth.Truth.assertThat
import com.spotiaisexs.app.data.local.db.SongPlayStatsEntity
import com.spotiaisexs.app.data.music.YouTubeMusicTrack
import com.spotiaisexs.app.playback.PlayableTrack
import io.mockk.mockk
import org.junit.Test

class ThisIsPlaylistsTest {
    private val builder = ThisIsPlaylists(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private fun hit(title: String, id: String) = PlayableTrack(title = title, artist = "Quevedo", videoId = id)
    private fun mine(title: String, id: String?, ms: Long) =
        SongPlayStatsEntity(trackKey = title.lowercase(), title = title, artist = "Quevedo", videoId = id, totalPlayTimeMs = ms, playCount = 3)

    @Test
    fun alternatesTheUserFavouritesWithTheHitsWithoutRepeats() {
        val hits = listOf(hit("Columbia", "a"), hit("Quédate", "b"), hit("Punto G", "c"))
        val user = listOf(mine("Playa del Inglés", "x", 900_000), mine("Columbia (Live)", "y", 600_000))
        val titles = builder.mix(hits, user).map { it.title }
        // User first, then a hit; "Columbia (Live)" is the same song as the hit "Columbia".
        assertThat(titles).containsExactly("Playa del Inglés", "Columbia", "Quédate", "Punto G").inOrder()
    }

    @Test
    fun aSongWithoutVideoIdBorrowsTheOneFromTheArtistPage() {
        val hits = listOf(hit("Columbia", "a"))
        val user = listOf(mine("Columbia", null, 900_000))
        val tracks = builder.mix(hits, user)
        assertThat(tracks).hasSize(1)
        assertThat(tracks.single().videoId).isEqualTo("a")
    }

    @Test
    fun aSongOnBothListsCountsAsTheUsers() {
        val hits = listOf(hit("Columbia", "a"), hit("Quédate", "b"))
        val user = listOf(mine("Playa del Inglés", "x", 900_000), mine("Columbia (Live)", "y", 600_000))
        val tracks = builder.mix(hits, user)
        // "Columbia" stays in its hit version, but the user listens to it a lot too.
        assertThat(builder.mineIds(tracks, user)).containsExactly("x", "a")
    }

    @Test
    fun theUsersVideoVersionGivesWayToTheSong() {
        val hits = listOf(hit("drivers license", "song1"))
        val user = listOf(mine("drivers license (Official Video)", "video1", 900_000))
        val tracks = builder.mix(hits, user)
        assertThat(tracks.map { it.videoId }).containsExactly("song1")
        assertThat(builder.mineIds(tracks, user)).containsExactly("song1")
    }

    @Test
    fun fillsWithMoreSongsUpToTheMaximum() {
        val hits = (1..5).map { hit("Hit $it", "h$it") }
        val more = (1..80).map { YouTubeMusicTrack(videoId = "m$it", title = "Song $it", artist = "Quevedo") } +
            YouTubeMusicTrack(videoId = "dup", title = "Hit 1 (Remix)", artist = "Quevedo")
        val tracks = builder.mix(hits, emptyList(), more)
        assertThat(tracks).hasSize(ThisIsPlaylists.MAX_TRACKS)
        assertThat(tracks.take(5).map { it.videoId }).containsExactly("h1", "h2", "h3", "h4", "h5").inOrder()
        assertThat(tracks.map { it.videoId }).doesNotContain("dup")
    }

    @Test
    fun recognisesTheArtistInsideCollaborations() {
        assertThat(ThisIsPlaylists.isBy("Bad Bunny & Jhayco", "Bad Bunny")).isTrue()
        assertThat(ThisIsPlaylists.isBy("ROSALÍA, Bad Bunny", "Rosalia")).isTrue()
        assertThat(ThisIsPlaylists.isBy("Emeli Sandé", "Eme")).isFalse()
        assertThat(ThisIsPlaylists.isBy("Olivia Rodrigo", "Olivia Rodrigo")).isTrue()
    }

    @Test
    fun takesOneSongOfEachAlbumInTurn() {
        val order = ThisIsPlaylists.interleave(listOf(listOf("a1", "a2", "a3"), listOf("b1"), listOf("c1", "c2")))
        assertThat(order).containsExactly("a1", "b1", "c1", "a2", "c2", "a3").inOrder()
    }

    @Test
    fun idsRoundTrip() {
        val id = ThisIsPlaylists.idFor("Rels B", "UC123")
        assertThat(ThisIsPlaylists.parse(id)).isEqualTo("Rels B" to "UC123")
        assertThat(ThisIsPlaylists.parse(ThisIsPlaylists.idFor("Saiko"))).isEqualTo("Saiko" to null)
        assertThat(ThisIsPlaylists.parse("PL123")).isNull()
    }
}
