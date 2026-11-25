package com.spotiaisexs.app.data.mood

import android.content.Context
import com.spotiaisexs.app.data.lyrics.LrclibLyricsApi
import com.spotiaisexs.app.playback.RadioFilters
import com.spotiaisexs.app.playback.RadioRecommender
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a song's lyrics say, for the mood playlists: the language they are
 * sung in, whether they talk about partying or about pushing on, and whether
 * they talk the way reggaeton and trap do.
 * [language]: positive = Spanish, negative = English, 0 = can't tell.
 */
@Serializable
data class LyricSignal(
    val language: Int = 0,
    val party: Boolean = false,
    val motivation: Boolean = false,
    /** Reggaeton / trap vocabulary all over the lyrics. */
    val urban: Boolean = false,
    val instrumental: Boolean = false,
    /** The lyrics were not found: nothing is known, and the song is not looked up again. */
    val missing: Boolean = false,
    /** Which analysis made this verdict; older ones are read again to learn what they lack. */
    val version: Int = 1,
)

/**
 * Reads lyrics from LRCLIB (free, no key) and keeps only the verdict, never
 * the text, saved on the phone so each song is looked up once.
 */
@Singleton
class LyricSignals @Inject constructor(
    @ApplicationContext context: Context,
    private val lrclib: LrclibLyricsApi,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.applicationContext.getSharedPreferences("mood_lyrics", Context.MODE_PRIVATE)
    private val serializer = MapSerializer(String.serializer(), LyricSignal.serializer())
    private val gate = Semaphore(6)
    private val signals = ConcurrentHashMap<String, LyricSignal>().apply {
        runCatching { prefs.getString(KEY, null)?.let { putAll(json.decodeFromString(serializer, it)) } }
    }

    private fun key(title: String, artist: String) = RadioRecommender.historyKey(title, artist)

    fun cached(title: String, artist: String): LyricSignal? = signals[key(title, artist)]

    /** Signals for as many of [songs] (title, artist) as [budgetMs] allows; already known ones are free. */
    suspend fun signalsFor(songs: List<Pair<String, String>>, budgetMs: Long): Map<String, LyricSignal> {
        val missing = songs.distinctBy { key(it.first, it.second) }.filter { signals[key(it.first, it.second)].isStale() }
        if (missing.isNotEmpty()) {
            withTimeoutOrNull(budgetMs) {
                coroutineScope {
                    missing.map { (title, artist) ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                runCatching { lrclib.fetchLyrics(title, artist) }.onSuccess { record ->
                                    val text = record?.plainLyrics?.takeIf { it.isNotBlank() }
                                        ?: record?.syncedLyrics?.replace(TIMESTAMP, " ")
                                    signals[key(title, artist)] = when {
                                        record?.instrumental == true -> LyricSignal(instrumental = true, version = VERSION)
                                        text.isNullOrBlank() -> LyricSignal(missing = true, version = VERSION)
                                        else -> analyse(text)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            persist()
        }
        return songs.mapNotNull { (title, artist) -> signals[key(title, artist)]?.let { key(title, artist) to it } }.toMap()
    }

    private fun persist() {
        runCatching {
            val snapshot = if (signals.size > MAX_SAVED) signals.entries.take(MAX_SAVED).associate { it.key to it.value } else HashMap(signals)
            prefs.edit().putString(KEY, json.encodeToString(serializer, snapshot)).apply()
        }
    }

    companion object {
        const val VERSION = 2
        private const val KEY = "signals"
        private const val MAX_SAVED = 4_000
        private val TIMESTAMP = Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?]""")
        private val PARTY = Regex(
            "(?i)\\b(fiesta|party|parti|baila|bailar|bailando|dance|dancing|perreo|perrear|discoteca|club|shots?|botella|previa|gozar|dj|toda la noche|all night|turn up|vacil[oó]n|jangueo|janguear|pista)\\b",
        )
        /** Words reggaeton and trap keep coming back to (and pop barely uses). */
        private val URBAN = Regex(
            "(?i)\\b(perreo|perrear|perreando|bellac[oa]s?|bellaqueo|gata|mami|mamacita|shorty|flow|trap|trapero|calle|bichote|bandid[oa]|glock|phillie|blunt|kush|tusi|lean|culo|nalgas|twerk|cabr[oó]n|puta|puto|mera|drip|money|cash|chavos|billetes|lambo|rolex|cartier|gucci|prada|ki[eé]n|real hasta la muerte|dímelo|brr+|prr+|yeh|wuh)\\b",
        )
        private val MOTIVATION = Regex(
            "(?i)\\b(fight|stronger|rise|champion|winners?|believe|unstoppable|never give up|keep going|survivor|power|warrior|fuerza|ganar|luchar|lucha|lev[aá]ntate|campe[oó]n|imparable|sueños|no me rindo|seguir adelante|guerrer[oa])\\b",
        )

        /** Verdict on a lyric: its language and whether partying or pushing on come up again and again. */
        internal fun analyse(text: String): LyricSignal {
            val sample = text.take(2_500)
            return LyricSignal(
                language = RadioFilters.titleLanguage(sample).let { score ->
                    when {
                        score >= LANGUAGE_MARGIN -> 1
                        score <= -LANGUAGE_MARGIN -> -1
                        else -> 0
                    }
                },
                party = PARTY.findAll(sample).count() >= 4,
                motivation = MOTIVATION.findAll(sample).count() >= 3,
                urban = URBAN.findAll(sample).map { it.value.lowercase() }.toSet().size >= 3,
                version = VERSION,
            )
        }

        private const val LANGUAGE_MARGIN = 4

        private fun LyricSignal?.isStale(): Boolean = this == null || (version < VERSION && !missing && !instrumental)
    }
}
