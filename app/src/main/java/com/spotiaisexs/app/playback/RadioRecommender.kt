package com.spotiaisexs.app.playback

import kotlin.math.ln
import kotlin.random.Random

/**
 * A radio candidate after the hard filters (exclusions, duplicate versions,
 * mashups…) already ran. [sources] holds every seed that surfaced it; [bestRank]
 * is its best position inside any of those related lists (0 = first).
 */
/**
 * How far the radio may wander from what the user picked.
 *
 * - [FOCUSED]: the user chose a concrete song (search, link, a tap on a track).
 *   The continuation stays in that song's neighbourhood — same artist, the
 *   artists YouTube itself places next to it — and taste only breaks ties.
 * - [EXPLORE]: the user asked for "Infinite Radio" or a mix. Wide variety
 *   driven by their taste, with a big discovery share.
 */
enum class RadioStyle { FOCUSED, EXPLORE }

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
    val style: RadioStyle = RadioStyle.EXPLORE,
    /** The dial and its switches; decides how far the batch wanders and its block mix. */
    val tuning: RadioTuning = RadioTuning(distance = if (style == RadioStyle.FOCUSED) RadioTuning.CLOSE else RadioTuning.WIDE),
    /** What this session taught us, per artist token (negative = skipped early, positive = liked). */
    val sessionArtistScores: Map<String, Double> = emptyMap(),
    /** Artist tokens of the song the radio grows from. */
    val seedArtistTokens: Set<String> = emptySet(),
)

/** A queued radio song together with why it was chosen. */
internal data class RadioPick(val track: PlayableTrack, val reason: RadioReason)

private enum class RadioBucket { FAMILIAR, REDISCOVERY, FRESH }

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
    const val FOCUSED_DISCOVERY_RATIO = 0.1
    const val EXPLORE_DISCOVERY_RATIO = 0.4
    const val TEMPERATURE = 0.7

    private const val DAY_MS = 24.0 * 60 * 60 * 1000

    fun historyKey(title: String, artist: String): String =
        "${title.trim()}|${artist.trim()}".lowercase()

    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|\+|/|\bx\b|\bfeat\.?|\bft\.?|\bwith\b|\by\b)\s*""")

    /** "Beny Jr & Morad feat. X" → {"beny jr", "morad", "x"} — for neighbourhood checks. */
    fun artistTokens(artist: String): Set<String> =
        artist.lowercase().split(ARTIST_SPLIT).map(String::trim).filter { it.length > 1 }.toSet()

    fun discoveryRatioFor(style: RadioStyle): Double = when (style) {
        RadioStyle.FOCUSED -> FOCUSED_DISCOVERY_RATIO
        RadioStyle.EXPLORE -> EXPLORE_DISCOVERY_RATIO
    }

    fun select(
        candidates: List<RadioCandidate>,
        signals: RadioSignals,
        batchSize: Int,
        random: Random = Random.Default,
        discoveryRatio: Double = discoveryRatioFor(signals.style),
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

    /** A song counts as "forgotten" when it was played for real and not for this long. */
    const val REDISCOVERY_AFTER_MS = 60L * 24 * 60 * 60 * 1000
    const val SOURCE_LIBRARY = "spotify-library"
    const val SOURCE_KNOWN = "known"
    const val SOURCE_REDISCOVER = "rediscover"

    private fun bucketOf(candidate: RadioCandidate, signals: RadioSignals): RadioBucket {
        val history = signals.history[historyKey(candidate.track.title, candidate.track.artist)]
        val forgotten = history != null && history.playCount >= 2 && history.skipCount <= history.playCount &&
            signals.nowMillis - history.lastPlayedAtMillis > REDISCOVERY_AFTER_MS
        return when {
            forgotten || SOURCE_REDISCOVER in candidate.sources -> RadioBucket.REDISCOVERY
            history != null || SOURCE_LIBRARY in candidate.sources || SOURCE_KNOWN in candidate.sources -> RadioBucket.FAMILIAR
            else -> RadioBucket.FRESH
        }
    }

    /** Spreads the three kinds over five slots so the same kind is not bunched up. */
    internal fun slotPattern(mix: BlockMix): List<String> {
        val counts = linkedMapOf(
            RadioBucket.FAMILIAR.name to mix.familiar.coerceAtLeast(0),
            RadioBucket.FRESH.name to mix.fresh.coerceAtLeast(0),
            RadioBucket.REDISCOVERY.name to mix.rediscovery.coerceAtLeast(0),
        )
        if (counts.values.sum() == 0) counts[RadioBucket.FAMILIAR.name] = 5
        val out = mutableListOf<String>()
        var last: String? = null
        while (counts.values.sum() > 0) {
            val open = counts.entries.filter { it.value > 0 }.sortedByDescending { it.value }
            val pick = open.firstOrNull { it.key != last } ?: open.first()
            out += pick.key
            counts[pick.key] = pick.value - 1
            last = pick.key
        }
        return out
    }

    /**
     * Builds the batch in blocks of five following the dial's [BlockMix]
     * (say three familiar, one forgotten, one new) instead of drawing at
     * random. Inside each kind the songs are still sampled (Gumbel over the
     * scores), artists are kept apart, and every pick carries its reason.
     * When a kind runs dry its slots fall back to the best song left.
     */
    fun selectBlocks(
        candidates: List<RadioCandidate>,
        signals: RadioSignals,
        batchSize: Int,
        seedTitle: String?,
        random: Random = Random.Default,
    ): List<RadioPick> {
        if (candidates.isEmpty() || batchSize <= 0) return emptyList()
        val tuning = signals.tuning

        val fresh = candidates.filterNot { isInHardCooldown(it, signals) || isChronicSkip(it, signals) }
        // Never let the queue starve: if almost everything was heard yesterday,
        // fall back to the full pool and let the soft penalties sort it out.
        val pool = if (fresh.size >= batchSize / 2) fresh else candidates.filterNot { isChronicSkip(it, signals) }
        if (pool.isEmpty()) return emptyList()

        val ranked = pool
            .map { it to (score(it, signals) / TEMPERATURE + gumbel(random)) }
            .sortedByDescending { it.second }
            .map { it.first }
        val buckets = ranked.groupBy { bucketOf(it, signals) }

        val pattern = slotPattern(tuning.mix).map { RadioBucket.valueOf(it) }
        val artistCap = when {
            tuning.sameArtist -> Int.MAX_VALUE
            tuning.noRepeatArtist -> ARTIST_CAP_PER_BATCH
            else -> ARTIST_CAP_PER_BATCH * 2
        }
        val spacing = when {
            tuning.sameArtist -> 0
            tuning.noRepeatArtist -> ARTIST_SPACING + 1
            else -> 2
        }
        val window = ArrayDeque(signals.recentQueueArtists.takeLast(spacing))
        val artistCounts = mutableMapOf<String, Int>()
        val taken = HashSet<String>()
        val out = mutableListOf<RadioPick>()

        fun take(bucket: RadioBucket, respectSpacing: Boolean): RadioCandidate? =
            buckets[bucket].orEmpty().firstOrNull { candidate ->
                val artist = candidate.track.artist.trim().lowercase()
                historyKey(candidate.track.title, candidate.track.artist) !in taken &&
                    artistCounts.getOrDefault(artist, 0) < artistCap &&
                    (!respectSpacing || artist !in window)
            }

        var slot = 0
        while (out.size < batchSize) {
            val want = pattern[slot % pattern.size]
            slot++
            val order = when (want) {
                RadioBucket.FAMILIAR -> listOf(RadioBucket.FAMILIAR, RadioBucket.FRESH, RadioBucket.REDISCOVERY)
                RadioBucket.FRESH -> listOf(RadioBucket.FRESH, RadioBucket.FAMILIAR, RadioBucket.REDISCOVERY)
                RadioBucket.REDISCOVERY -> listOf(RadioBucket.REDISCOVERY, RadioBucket.FAMILIAR, RadioBucket.FRESH)
            }
            var chosen: RadioCandidate? = null
            var chosenBucket = want
            search@ for (respectSpacing in listOf(true, false)) {
                for (bucket in order) {
                    val candidate = take(bucket, respectSpacing) ?: continue
                    chosen = candidate
                    chosenBucket = bucket
                    break@search
                }
            }
            val pick = chosen ?: break
            val artist = pick.track.artist.trim().lowercase()
            taken += historyKey(pick.track.title, pick.track.artist)
            artistCounts[artist] = artistCounts.getOrDefault(artist, 0) + 1
            if (spacing > 0) {
                window.addLast(artist)
                while (window.size > spacing) window.removeFirst()
            }
            val sameArtist = artistTokens(pick.track.artist).any { it in signals.seedArtistTokens }
            val kind = when {
                chosenBucket == RadioBucket.REDISCOVERY -> RadioReasonKind.REDISCOVERY
                chosenBucket == RadioBucket.FAMILIAR -> RadioReasonKind.LIBRARY
                want == RadioBucket.FRESH -> RadioReasonKind.NEW
                sameArtist -> RadioReasonKind.SAME_ARTIST
                else -> RadioReasonKind.NEIGHBOUR
            }
            out += RadioPick(pick.track, RadioReason(kind, seedTitle))
        }
        return out
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
        val focused = signals.tuning.focused
        // Focused radios trust YouTube's order much more: the top of a song's
        // related list is its real neighbourhood, the tail is where it drifts.
        score += if (focused) 2.0 / (1.0 + candidate.bestRank / 6.0) else 1.0 / (1.0 + candidate.bestRank / 8.0)

        // Taste: the further the dial, the more it weighs. Close to the seed it only breaks ties.
        val far = (signals.tuning.distance / 100.0).coerceIn(0.0, 1.0)
        if (artist in signals.spotifyArtists) score += 0.15 + 0.6 * far
        if (artist in signals.heavyRotationArtists) score += 0.1 + 0.15 * far

        // Same-artist end of the dial: the picked artist comes first.
        val tokens = artistTokens(candidate.track.artist)
        if (signals.tuning.sameArtist && tokens.any { it in signals.seedArtistTokens }) score += 1.5

        // What this session taught us: early skips sink an artist, full listens lift it.
        if (signals.sessionArtistScores.isNotEmpty()) {
            score += tokens.sumOf { signals.sessionArtistScores[it] ?: 0.0 }.coerceIn(-3.0, 3.0)
        }

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
