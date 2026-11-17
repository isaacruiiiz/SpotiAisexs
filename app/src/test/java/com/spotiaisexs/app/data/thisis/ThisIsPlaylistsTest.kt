package com.spotiaisexs.app.data.thisis

import com.google.common.truth.Truth.assertThat
import com.spotiaisexs.app.data.local.db.SongPlayStatsEntity
import com.spotiaisexs.app.playback.PlayableTrack
import io.mockk.mockk
import org.junit.Test

class ThisIsPlaylistsTest {
    private val builder = ThisIsPlaylists(mockk(relaxed = true), mockk(relaxed = true))

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
    fun idsRoundTrip() {
        val id = ThisIsPlaylists.idFor("Rels B", "UC123")
        assertThat(ThisIsPlaylists.parse(id)).isEqualTo("Rels B" to "UC123")
        assertThat(ThisIsPlaylists.parse(ThisIsPlaylists.idFor("Saiko"))).isEqualTo("Saiko" to null)
        assertThat(ThisIsPlaylists.parse("PL123")).isNull()
    }
}
