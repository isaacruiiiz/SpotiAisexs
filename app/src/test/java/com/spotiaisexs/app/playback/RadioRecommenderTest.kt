package com.spotiaisexs.app.playback

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class RadioRecommenderTest {

    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    private fun pool(size: Int = 60, artists: Int = 15) = (0 until size).map { i ->
        RadioCandidate(
            track = PlayableTrack(title = "Song $i", artist = "Artist ${i % artists}", videoId = "v$i"),
            sources = setOf("seed:a"),
            bestRank = i,
        )
    }

    private fun key(i: Int, artists: Int = 15) = RadioRecommender.historyKey("Song $i", "Artist ${i % artists}")

    @Test
    fun sameSeedDifferentRunsAreNotIdentical() {
        val signals = RadioSignals(nowMillis = now, history = emptyMap())
        val a = RadioRecommender.select(pool(), signals, 20, Random(1)).map { it.title }
        val b = RadioRecommender.select(pool(), signals, 20, Random(2)).map { it.title }
        assertThat(a).hasSize(20)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun songsHeardInTheLastDayAreSkipped() {
        val history = mapOf(key(0) to RadioTrackHistory(playCount = 10, skipCount = 0, lastPlayedAtMillis = now - 2 * 3_600_000))
        repeat(20) { seed ->
            val picked = RadioRecommender.select(pool(), RadioSignals(now, history), 20, Random(seed))
            assertThat(picked.map { it.title }).doesNotContain("Song 0")
        }
    }

    @Test
    fun chronicallySkippedSongsAreSkipped() {
        val history = mapOf(key(1) to RadioTrackHistory(playCount = 0, skipCount = 3, lastPlayedAtMillis = now - 30 * day))
        repeat(20) { seed ->
            val picked = RadioRecommender.select(pool(), RadioSignals(now, history), 20, Random(seed))
            assertThat(picked.map { it.title }).doesNotContain("Song 1")
        }
    }

    @Test
    fun atMostTwoSongsPerArtistAndSpaced() {
        val picked = RadioRecommender.select(pool(), RadioSignals(now, emptyMap()), 20, Random(7))
        val counts = picked.groupingBy { it.artist }.eachCount()
        assertThat(counts.values.maxOrNull()).isAtMost(RadioRecommender.ARTIST_CAP_PER_BATCH)
        val artists = picked.map { it.artist }
        for (i in artists.indices) {
            for (gap in 1..RadioRecommender.ARTIST_SPACING) {
                if (i + gap < artists.size) assertThat(artists[i + gap]).isNotEqualTo(artists[i])
            }
        }
    }

    @Test
    fun neverStarvesWhenEverythingWasHeardRecently() {
        val small = pool(size = 6, artists = 6)
        val history = (0 until 6).associate { key(it, 6) to RadioTrackHistory(1, 0, now - 3_600_000) }
        val picked = RadioRecommender.select(small, RadioSignals(now, history), 20, Random(3))
        assertThat(picked).hasSize(6)
    }
}
