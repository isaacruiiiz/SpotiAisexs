package com.spotiaisexs.app.playback

import java.util.concurrent.ConcurrentHashMap

/** How many songs of each kind go into every block of five radio songs. */
data class BlockMix(val familiar: Int, val rediscovery: Int, val fresh: Int)

/**
 * The radio "dial" and its switches. [distance] runs from 0 (same artist) to
 * 100 (surprise me) and replaces the old two fixed styles: everything the
 * recommender does (how far it wanders, how many new songs it mixes in) is
 * derived from it. The switches are hard filters applied on top.
 */
data class RadioTuning(
    val distance: Int = CLOSE,
    val onlySpanish: Boolean = false,
    val noSlow: Boolean = false,
    val noRepeatArtist: Boolean = true,
    val thisDecade: Boolean = false,
    val station: RadioStation? = null,
) {
    /** Stays inside the picked song's neighbourhood. */
    val focused: Boolean get() = distance < FOCUS_LIMIT

    /** Tightest setting: the picked artist and whatever sits right next to it. */
    val sameArtist: Boolean get() = distance < SAME_ARTIST_LIMIT

    val calm: Boolean get() = station == RadioStation.TRANQUILO
    val knownOnly: Boolean get() = station == RadioStation.COCHE
    val wantsEnergy: Boolean get() = noSlow || station == RadioStation.COCHE

    val mix: BlockMix
        get() = station?.mix ?: when {
            distance < SAME_ARTIST_LIMIT -> BlockMix(familiar = 5, rediscovery = 0, fresh = 0)
            distance < FOCUS_LIMIT -> BlockMix(familiar = 3, rediscovery = 1, fresh = 1)
            distance < FAR_LIMIT -> BlockMix(familiar = 2, rediscovery = 1, fresh = 2)
            else -> BlockMix(familiar = 1, rediscovery = 1, fresh = 3)
        }

    /** Short name of the current dial position. */
    val label: String
        get() = when {
            distance < SAME_ARTIST_LIMIT -> "Mismo artista"
            distance < FOCUS_LIMIT -> "Cerca"
            distance < FAR_LIMIT -> "Tu gusto"
            else -> "Lejos"
        }

    /** One sentence describing what the dial is doing right now. */
    fun describe(seedArtist: String?): String {
        val who = seedArtist?.takeIf { it.isNotBlank() }
        val m = mix
        val news = when (m.fresh) {
            0 -> "sin canciones nuevas"
            1 -> "una canción nueva cada cinco"
            else -> "${m.fresh} canciones nuevas cada cinco"
        }
        val where = when {
            sameArtist -> if (who != null) "$who y lo más pegado a su sonido" else "el mismo artista"
            focused -> if (who != null) "artistas vecinos de $who" else "artistas vecinos"
            distance < FAR_LIMIT -> "tu gusto en general"
            else -> "lejos de lo habitual"
        }
        return "Ahora: $where, $news."
    }

    companion object {
        const val SAME_ARTIST_LIMIT = 18
        const val FOCUS_LIMIT = 50
        const val FAR_LIMIT = 78

        /** Where a song the user picked by hand starts. */
        const val CLOSE = 32

        /** Where "Tu mezcla" and other wide radios start. */
        const val WIDE = 60
    }
}

/** Ready-made radios of the home screen, each with its own rules. */
enum class RadioStation(
    val id: String,
    val title: String,
    val subtitle: String,
    val distance: Int,
    val mix: BlockMix?,
) {
    TU_MEZCLA("mix", "Tu mezcla", "Lo tuyo de siempre, con una nueva cada cinco canciones", 58, BlockMix(3, 1, 1)),
    DESCUBRIR("discover", "Descubrir", "Solo lo que no has oído", 85, BlockMix(0, 0, 5)),
    REDESCUBRIR("rediscover", "Redescubrir", "Lo que ponías hace meses", 55, BlockMix(1, 4, 0)),
    COCHE("car", "Coche", "Conocidas y con energía", 55, BlockMix(4, 1, 0)),
    TRANQUILO("calm", "Tranquilo", "Sin sobresaltos", 55, BlockMix(3, 1, 1)),
    ME_GUSTA("liked", "Me gusta", "Tus guardadas, mezcladas", 55, null);

    val sourceLabel: String get() = title

    companion object {
        fun byId(id: String?): RadioStation? = entries.firstOrNull { it.id == id }
    }
}

enum class RadioReasonKind { NEIGHBOUR, SAME_ARTIST, LIBRARY, NEW, REDISCOVERY }

/** Why the radio queued a song; shown as "Suena porque…" and as the tag in the queue. */
data class RadioReason(val kind: RadioReasonKind, val seedTitle: String? = null) {
    /** Short tag for a queue row. */
    val tag: String
        get() = when (kind) {
            RadioReasonKind.NEIGHBOUR -> "Vecina"
            RadioReasonKind.SAME_ARTIST -> "Mismo artista"
            RadioReasonKind.LIBRARY -> "De tu biblioteca"
            RadioReasonKind.NEW -> "Nueva"
            RadioReasonKind.REDISCOVERY -> "Hace meses que no suena"
        }

    /** Completes the sentence "Suena porque …". */
    val because: String
        get() {
            val seed = seedTitle?.takeIf { it.isNotBlank() }
            return when (kind) {
                RadioReasonKind.NEIGHBOUR -> if (seed != null) "se parece a $seed" else "encaja con lo que suena"
                RadioReasonKind.SAME_ARTIST -> if (seed != null) "es del mismo artista que $seed" else "es del mismo artista"
                RadioReasonKind.LIBRARY -> if (seed != null) "está en tu biblioteca y encaja con $seed" else "está en tu biblioteca"
                RadioReasonKind.NEW -> if (seed != null) "es nueva para ti y se parece a $seed" else "es nueva para ti"
                RadioReasonKind.REDISCOVERY -> "hace meses que no la pones"
            }
        }
}

/**
 * What the radio learnt since the app started: early skips push an artist
 * down right away (two in a row take it out), full listens and "Más así" push
 * it up. Lives in memory only; the long-term history stays in the stats DB.
 */
internal class RadioSession {
    private val artistScore = ConcurrentHashMap<String, Double>()
    private val earlySkips = ConcurrentHashMap<String, Int>()
    private val blocked = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var consecutiveEarlySkips: Int = 0
        private set

    private fun tokens(artist: String) = RadioRecommender.artistTokens(artist)

    /** Returns true when this artist is now out of the session. */
    fun onEarlySkip(artist: String): Boolean {
        consecutiveEarlySkips++
        var nowBlocked = false
        for (token in tokens(artist)) {
            artistScore.merge(token, -EARLY_SKIP_PENALTY, Double::plus)
            if (earlySkips.merge(token, 1, Int::plus)!! >= SKIPS_TO_BLOCK) {
                blocked.add(token)
                nowBlocked = true
            }
        }
        return nowBlocked
    }

    fun onLateSkip(artist: String) {
        consecutiveEarlySkips = 0
        for (token in tokens(artist)) artistScore.merge(token, -LATE_SKIP_PENALTY, Double::plus)
    }

    fun onCompleted(artist: String) {
        consecutiveEarlySkips = 0
        for (token in tokens(artist)) {
            artistScore.merge(token, COMPLETED_BOOST, Double::plus)
            earlySkips.remove(token)
        }
    }

    fun more(artist: String) {
        for (token in tokens(artist)) {
            blocked.remove(token)
            earlySkips.remove(token)
            artistScore.merge(token, MORE_BOOST, Double::plus)
        }
    }

    fun less(artist: String) {
        for (token in tokens(artist)) {
            blocked.add(token)
            artistScore.merge(token, -MORE_BOOST, Double::plus)
        }
    }

    /** The user picked this artist by hand: whatever the radio held against it is forgiven. */
    fun forgive(artist: String) {
        consecutiveEarlySkips = 0
        for (token in tokens(artist)) {
            blocked.remove(token)
            earlySkips.remove(token)
            artistScore.computeIfPresent(token) { _, value -> if (value < 0.0) null else value }
        }
    }

    fun resetSkipStreak() {
        consecutiveEarlySkips = 0
    }

    fun isBlocked(artist: String): Boolean = tokens(artist).any { it in blocked }

    fun scoreFor(artist: String): Double =
        tokens(artist).sumOf { artistScore[it] ?: 0.0 }.coerceIn(-MAX_SWING, MAX_SWING)

    fun snapshot(): Map<String, Double> = HashMap(artistScore)

    companion object {
        /** A skip this early means "not this", not "not now". */
        const val EARLY_SKIP_MS = 30_000L
        const val SKIPS_TO_BLOCK = 2
        const val EARLY_SKIP_PENALTY = 1.6
        const val LATE_SKIP_PENALTY = 0.3
        const val COMPLETED_BOOST = 0.35
        const val MORE_BOOST = 1.8
        const val MAX_SWING = 3.0
    }
}

/**
 * Text-only guesses used by the radio switches when there is no better data.
 * They only ever say "this looks Spanish / English / slow"; no answer means
 * "don't know" and the caller keeps the song.
 */
internal object RadioFilters {
    private val WORD = Regex("""[\p{L}']+""")

    private val ES_WORDS = setOf(
        "de", "la", "el", "que", "mi", "tu", "te", "y", "en", "los", "las", "un", "una", "con", "por", "para",
        "sin", "del", "al", "es", "más", "mas", "yo", "lo", "se", "como", "cuando", "donde", "amor", "corazón",
        "corazon", "noche", "noches", "vida", "quiero", "eres", "soy", "estoy", "ella", "él", "contigo", "conmigo",
        "otra", "vez", "nada", "todo", "sola", "loco", "loca", "día", "dia", "días", "bebé", "mami", "papi", "ojos",
        "beso", "besos", "fuego", "cielo", "luna", "sol", "playa", "baila", "bailar", "dime", "dale", "hoy", "ayer",
        "mañana", "nunca", "siempre", "porque", "mío", "mía", "tuyo", "tuya", "quédate", "quedate", "tengo", "tienes",
        "quién", "quien", "qué", "así", "asi", "ya", "si", "pero", "muy", "bien", "mal", "ti", "mí", "sus", "le",
        "nos", "aquí", "aqui", "ahora", "calle", "barrio", "gente", "mundo", "tiempo", "casa", "mar", "flor",
    )

    private val EN_WORDS = setOf(
        "the", "you", "your", "my", "and", "of", "to", "in", "it", "is", "love", "i", "we", "on", "for", "with",
        "don't", "dont", "all", "what", "this", "that", "be", "like", "night", "baby", "one", "up", "down", "out",
        "girl", "boy", "heart", "time", "when", "never", "feel", "want", "know", "good", "bad", "day", "life",
        "world", "back", "away", "again", "can't", "i'm", "im", "let", "go", "say", "way", "are", "was", "but",
        "not", "just", "now", "her", "his", "from", "at", "by", "if", "they", "she", "he", "our", "into", "over",
        "about", "too", "got", "get", "gonna", "wanna", "ain't", "it's", "you're", "we're", "summer", "dance",
    )

    private val ES_CHARS = setOf('ñ', 'á', 'é', 'í', 'ó', 'ú', '¿', '¡', 'ü')

    private val VERSION_NOISE = Regex("""\(.*?\)|\[.*?]|\bfeat\.?.*$|\bft\.?.*$""", RegexOption.IGNORE_CASE)

    /** Positive = looks Spanish, negative = looks English, 0 = can't tell. */
    fun titleLanguage(title: String): Int {
        val lower = title.lowercase().replace(VERSION_NOISE, " ")
        var score = 0
        if (lower.any { it in ES_CHARS }) score += 2
        for (match in WORD.findAll(lower)) {
            val word = match.value
            if (word in ES_WORDS) score++
            if (word in EN_WORDS) score--
        }
        return score
    }

    private val SPANISH_TAGS = listOf(
        "spanish", "español", "espanol", "española", "latin", "latino", "reggaeton", "urbano", "flamenco", "rumba",
        "spain", "argentin", "mexic", "colombia", "puerto rico", "chile", "bachata", "salsa", "cumbia", "corrido",
        "dembow", "castellano", "cantautor", "venezuela", "cuba", "dominican", "uruguay", "peru",
    )

    private val SLOW_TAGS = listOf(
        "ballad", "balada", "acoustic", "acústico", "chill", "mellow", "sad", "slow", "ambient", "lo-fi", "lofi",
        "piano", "downtempo", "sleep", "relax", "soft", "easy listening", "bolero", "dream pop", "slowcore",
        "singer-songwriter", "melancho", "calm", "lounge", "bossa",
    )

    private val ENERGY_TAGS = listOf(
        "techno", "hardstyle", "metal", "drum and bass", "dnb", "drill", "hardcore", "punk", "edm", "dance",
        "house", "reggaeton", "dembow", "party", "workout", "hard rock", "phonk", "trance", "electro",
    )

    private val SLOW_TITLE = listOf(
        "acoustic", "acústico", "acustico", "acústica", "acustica", "piano", "balada", "ballad", "slowed",
        "unplugged", "lullaby", "nana ", "stripped", "interlude", "sad version", "versión lenta", "version lenta",
    )

    fun tagsSaySpanish(tags: Set<String>): Boolean = tags.any { tag -> SPANISH_TAGS.any { it in tag } }

    /** Positive = slow, negative = energetic, 0 = no evidence. */
    fun tempoEvidence(title: String, tags: Set<String>, bpm: Int?): Int {
        var score = 0
        val lower = title.lowercase()
        if (SLOW_TITLE.any { it in lower }) score += 3
        score += tags.count { tag -> SLOW_TAGS.any { it in tag } }.coerceAtMost(2)
        score -= tags.count { tag -> ENERGY_TAGS.any { it in tag } }.coerceAtMost(2)
        if (bpm != null && bpm > 0) {
            if (bpm < SLOW_BPM) score += 2
            if (bpm in FAST_BPM..HALF_TIME_BPM) score -= 1
        }
        return score
    }

    /** Below this a song drags; Deezer reports half-time for a lot of trap, so stay conservative. */
    const val SLOW_BPM = 82
    const val FAST_BPM = 112
    const val HALF_TIME_BPM = 200

    fun currentDecadeStart(nowYear: Int): Int = nowYear - nowYear % 10
}
