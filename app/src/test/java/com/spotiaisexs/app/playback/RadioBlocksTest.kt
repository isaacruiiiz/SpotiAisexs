package com.spotiaisexs.app.playback

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class RadioBlocksTest {

    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    private fun candidate(i: Int, source: String = "seed:a") = RadioCandidate(
        track = PlayableTrack(title = "Song $i", artist = "Artist $i", videoId = "v$i"),
        sources = setOf(source),
        bestRank = i % 20,
    )

    private fun key(i: Int) = RadioRecommender.historyKey("Song $i", "Artist $i")

    @Test
    fun patternSpreadsTheKindsOverFiveSlots() {
        assertThat(RadioRecommender.slotPattern(BlockMix(3, 1, 1)))
            .containsExactly("FAMILIAR", "FRESH", "FAMILIAR", "REDISCOVERY", "FAMILIAR").inOrder()
        assertThat(RadioRecommender.slotPattern(BlockMix(5, 0, 0))).hasSize(5)
        assertThat(RadioRecommender.slotPattern(BlockMix(0, 0, 0))).hasSize(5)
    }

    @Test
    fun everyBlockOfFiveFollowsTheMix() {
        // 0..19 played last month (familiar), 20..39 played long ago (forgotten), 40..79 never played.
        val pool = (0 until 80).map { candidate(it) }
        val history = (0 until 20).associate { key(it) to RadioTrackHistory(3, 0, now - 20 * day) } +
            (20 until 40).associate { key(it) to RadioTrackHistory(4, 0, now - 200 * day) }
        val signals = RadioSignals(now, history, tuning = RadioTuning(distance = 40))
        val picks = RadioRecommender.selectBlocks(pool, signals, 10, seedTitle = "Seed", random = Random(7))

        assertThat(picks).hasSize(10)
        picks.chunked(5).forEach { block ->
            val kinds = block.map { it.reason.kind }
            assertThat(kinds.count { it == RadioReasonKind.LIBRARY }).isEqualTo(3)
            assertThat(kinds.count { it == RadioReasonKind.REDISCOVERY }).isEqualTo(1)
            assertThat(kinds.count { it == RadioReasonKind.NEW }).isEqualTo(1)
        }
        assertThat(picks.map { it.track.title }.toSet()).hasSize(10)
    }

    @Test
    fun aKindThatRunsDryFallsBackInsteadOfStarving() {
        // Nothing was ever played: every slot is filled with a never-played song anyway.
        val pool = (0 until 30).map { candidate(it) }
        val signals = RadioSignals(now, emptyMap(), tuning = RadioTuning(distance = 40))
        val picks = RadioRecommender.selectBlocks(pool, signals, 15, seedTitle = "Seed", random = Random(2))
        assertThat(picks).hasSize(15)
        assertThat(picks.none { it.reason.kind == RadioReasonKind.REDISCOVERY }).isTrue()
    }

    @Test
    fun earlySkipsSinkAnArtistAndTwoTakeItOut() {
        val session = RadioSession()
        assertThat(session.onEarlySkip("Morad")).isFalse()
        assertThat(session.scoreFor("Morad")).isLessThan(0.0)
        assertThat(session.isBlocked("Morad")).isFalse()
        assertThat(session.onEarlySkip("Beny Jr & Morad")).isTrue()
        assertThat(session.isBlocked("Morad")).isTrue()
        assertThat(session.isBlocked("Beny Jr")).isFalse()
        session.forgive("Morad")
        assertThat(session.isBlocked("Morad")).isFalse()
    }

    @Test
    fun sessionScoreMovesACandidate() {
        val liked = candidate(1)
        val base = RadioSignals(now, emptyMap())
        val taught = base.copy(sessionArtistScores = mapOf("artist 1" to -1.6))
        assertThat(RadioRecommender.score(liked, taught)).isLessThan(RadioRecommender.score(liked, base) - 1.0)
    }

    @Test
    fun titlesGiveALanguageHint() {
        assertThat(RadioFilters.titleLanguage("Playa del Inglés")).isGreaterThan(0)
        assertThat(RadioFilters.titleLanguage("Quédate")).isGreaterThan(0)
        assertThat(RadioFilters.titleLanguage("Don't Start Now")).isLessThan(0)
        assertThat(RadioFilters.titleLanguage("Columbia")).isEqualTo(0)
    }

    @Test
    fun tempoEvidenceReadsTitleTagsAndBpm() {
        assertThat(RadioFilters.tempoEvidence("Luna (Acústico)", emptySet(), null)).isAtLeast(2)
        assertThat(RadioFilters.tempoEvidence("Luna", setOf("reggaeton"), 96)).isLessThan(2)
        assertThat(RadioFilters.tempoEvidence("Luna", emptySet(), 70)).isAtLeast(2)
        assertThat(RadioFilters.currentDecadeStart(2026)).isEqualTo(2020)
    }
}
