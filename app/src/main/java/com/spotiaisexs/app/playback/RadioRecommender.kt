package com.spotiaisexs.app.playback

import kotlin.math.ln
import kotlin.random.Random

/**
 * A radio candidate after the hard filters (exclusions, duplicate versions,
 * mashups…) already ran. [sources] holds every seed that surfaced it; [bestRank]
 * is its best position inside any of those related lists (0 = first).
 */
internal data class RadioCandidate(
    val track: PlayableTrack,
    val sources: Set<String>,
    val bestRank: Int,
)

/** What the local history says about one track (subset of SongPlayStatsEntity). */
internal data class RadioTrackHistory(
    val playCount: Int,
    val skipCount: Int,
    val lastPlayedAtMillis: Long,
)

internal data class RadioSignals(
    val nowMillis: Long,
    /** History keyed by [RadioRecommender.historyKey]; unplayed tracks are absent. */
    val history: Map<String, RadioTrackHistory>,
    /** Lowercased artists from the user's synced Spotify library. */
    val spotifyArtists: Set<String> = emptySet(),
    /** Lowercased artists the user listened to a lot lately. */
    val heavyRotationArtists: Set<String> = emptySet(),
    /** Lowercased artists of the queue tail, oldest first — used for spacing and fatigue. */
    val recentQueueArtists: List<String> = emptyList(),
)

/**
 * Spotify-style radio selection. The previous implementation took YouTube's
 * related list in its fixed order, so the same seed always produced the same
 * songs, and its fallback seeded from the most-played artists — a feedback loop
 * that kept replaying the same handful of tracks. This one:
 *
 *  1. Scores every candidate on relevance (how many seeds agree on it and how
 *     high they rank it), taste (Spotify library / heavy-rotation artists),
 *     freshness (cool-down for anything heard in the last days), skip history
 *     and artist fatigue.
 *  2. Splits the pool into "familiar" and "discovery" and reserves a share of
 *     every batch for discovery, like Spotify's mixes do.
 *  3. Samples instead of sorting (Gumbel-top-k over the scores), so two radios
 *     from the same seed are similar in spirit but never identical.
 *  4. Spaces artists so the same one never plays twice within a few tracks.
 */
internal object RadioRecommender {

    /** Tracks heard this recently are dropped outright unless the pool runs dry. */
    const val HARD_COOLDOWN_MS = 36L * 60 * 60 * 1000
    const val ARTIST_CAP_PER_BATCH = 2
    const val ARTIST_SPACING = 4
    const val DEFAULT_DISCOVERY_RATIO = 0.3
    const val TEMPERATURE = 0.7

    private const val DAY_MS = 24.0 * 60 * 60 * 1000

    fun historyKey(title: String, artist: String): String =
        "${title.trim()}|${artist.trim()}".lowercase()

    fun select(
        candidates: List<RadioCandidate>,
        signals: RadioSignals,
        batchSize: Int,
        random: Random = Random.Default,
        discoveryRatio: Double = DEFAULT_DISCOVERY_RATIO,
    ): List<PlayableTrack> {
        if (candidates.isEmpty() || batchSize <= 0) return emptyList()

        val fresh = candidates.filterNot { isInHardCooldown(it, signals) || isChronicSkip(it, signals) }
        // Never let the queue starve: if almost everything was heard yesterday,
        // fall back to the full pool and let the soft penalties sort it out.
        val pool = if (fresh.size >= batchSize / 2) fresh else candidates.filterNot { isChronicSkip(it, signals) }
        if (pool.isEmpty()) return emptyList()

        val scored = pool.map { it to score(it, signals) }
        val (discovery, familiar) = scored.partition { (candidate, _) -> isDiscovery(candidate, signals) }

        val discoveryTarget = (batchSize * discoveryRatio).toInt().coerceAtMost(discovery.size)
        val familiarTarget = (batchSize - discoveryTarget).coerceAtMost(familiar.size)

        val artistCounts = mutableMapOf<String, Int>()
        val picked = mutableListOf<PlayableTrack>()
        picked += sample(familiar, familiarTarget, artistCounts, random)
        picked += sample(discovery, discoveryTarget, artistCounts, random)

        // Top up from whatever is left if one bucket was short.
        if (picked.size < batchSize) {
            val taken = picked.mapTo(mutableSetOf()) { historyKey(it.title, it.artist) }
            val rest = scored.filter { (c, _) -> historyKey(c.track.title, c.track.artist) !in taken }
            picked += sample(rest, batchSize - picked.size, artistCounts, random)
        }

        return spaceArtists(picked, signals.recentQueueArtists)
    }

    internal fun score(candidate: RadioCandidate, signals: RadioSignals): Double {
        val artist = candidate.track.artist.trim().lowercase()
        var score = 0.0

        // Relevance: agreement between seeds is the strongest "this fits" signal.
        score += when (candidate.sources.size) {
            0, 1 -> 1.0
            2 -> 1.7
            else -> 2.2
        }
        score += 1.0 / (1.0 + candidate.bestRank / 8.0)

        // Taste.
        if (artist in signals.spotifyArtists) score += 0.6
        if (artist in signals.heavyRotationArtists) score += 0.2

        // Freshness and skips.
        val history = signals.history[historyKey(candidate.track.title, candidate.track.artist)]
        if (history == null) {
            score += 0.4
        } else {
            val daysAgo = (signals.nowMillis - history.lastPlayedAtMillis).coerceAtLeast(0) / DAY_MS
            score -= when {
                daysAgo < 3 -> 1.6
                daysAgo < 7 -> 0.9
                daysAgo < 14 -> 0.4
                else -> 0.0
            }
            // Songs played over and over recently get an extra rest.
            if (daysAgo < 14 && history.playCount >= 5) score -= 0.5
            val total = history.playCount + history.skipCount
            if (total > 0) score -= 2.0 * history.skipCount.toDouble() / total
        }

        // Artist fatigue: the more an artist already sounds in the queue tail, the less likely.
        val tailCount = signals.recentQueueArtists.count { it == artist }
        score -= 0.45 * tailCount

        return score
    }

    private fun isInHardCooldown(candidate: RadioCandidate, signals: RadioSignals): Boolean {
        val history = signals.history[historyKey(candidate.track.title, candidate.track.artist)] ?: return false
        return signals.nowMillis - history.lastPlayedAtMillis < HARD_COOLDOWN_MS
    }

    private fun isChronicSkip(candidate: RadioCandidate, signals: RadioSignals): Boolean {
        val history = signals.history[historyKey(candidate.track.title, candidate.track.artist)] ?: return false
        return history.skipCount >= 2 && history.skipCount > history.playCount
    }

    private fun isDiscovery(candidate: RadioCandidate, signals: RadioSignals): Boolean {
        val artist = candidate.track.artist.trim().lowercase()
        val known = artist in signals.spotifyArtists ||
            artist in signals.heavyRotationArtists ||
            artist in signals.recentQueueArtists
        return !known && signals.history[historyKey(candidate.track.title, candidate.track.artist)] == null
    }

    /**
     * Gumbel-top-k: adding Gumbel noise to score/T and taking the top k is
     * equivalent to sampling k items without replacement with probability
     * ∝ exp(score / T). High scores still win most of the time, but not always.
     */
    private fun sample(
        scored: List<Pair<RadioCandidate, Double>>,
        count: Int,
        artistCounts: MutableMap<String, Int>,
        random: Random,
    ): List<PlayableTrack> {
        if (count <= 0 || scored.isEmpty()) return emptyList()
        val ranked = scored
            .map { (candidate, score) -> candidate to (score / TEMPERATURE + gumbel(random)) }
            .sortedByDescending { it.second }
        val out = mutableListOf<PlayableTrack>()
        for ((candidate, _) in ranked) {
            val artist = candidate.track.artist.trim().lowercase()
            val current = artistCounts.getOrDefault(artist, 0)
            if (current >= ARTIST_CAP_PER_BATCH) continue
            artistCounts[artist] = current + 1
            out += candidate.track
            if (out.size >= count) break
        }
        return out
    }

    private fun gumbel(random: Random): Double {
        val u = random.nextDouble().coerceIn(1e-12, 1.0 - 1e-12)
        return -ln(-ln(u))
    }

    /**
     * Greedy reorder so no artist repeats within [ARTIST_SPACING] tracks,
     * counting the queue tail that is already in front of this batch. When no
     * candidate satisfies the gap, the one whose artist played longest ago goes next.
     */
    internal fun spaceArtists(tracks: List<PlayableTrack>, queueTail: List<String>): List<PlayableTrack> {
        val remaining = tracks.toMutableList()
        val window = ArrayDeque(queueTail.takeLast(ARTIST_SPACING))
        val out = mutableListOf<PlayableTrack>()
        while (remaining.isNotEmpty()) {
            // Among the tracks that respect the gap, place first the artist with the most
            // tracks still waiting — otherwise those pile up at the end with nowhere to go.
            val pending = remaining.groupingBy { it.artist.trim().lowercase() }.eachCount()
            val index = remaining.indices
                .filter { remaining[it].artist.trim().lowercase() !in window }
                .maxByOrNull { pending.getValue(remaining[it].artist.trim().lowercase()) * 1000 - it }
                ?: remaining.indices.maxByOrNull { i ->
                    val artist = remaining[i].artist.trim().lowercase()
                    val lastSeen = window.lastIndexOf(artist)
                    if (lastSeen < 0) Int.MAX_VALUE else window.size - lastSeen
                } ?: 0
            val next = remaining.removeAt(index)
            out += next
            window.addLast(next.artist.trim().lowercase())
            while (window.size > ARTIST_SPACING) window.removeFirst()
        }
        return out
    }
}
