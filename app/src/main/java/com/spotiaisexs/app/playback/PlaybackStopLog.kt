package com.spotiaisexs.app.playback

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persisted trail of everything that stopped the player or emptied the
 * queue, with the time and (when asked) who called it. Playback that "just
 * stops" leaves no other trace: logcat is gone after a restart. It is part of
 * Ajustes → About & System → Diagnostics.
 */
object PlaybackStopLog {
    private const val FILE_NAME = "playback_stops.log"
    private const val MAX_BYTES = 48L * 1024
    private const val CALLER_FRAMES = 6

    fun record(context: Context, what: String, withCaller: Boolean = false) {
        runCatching {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            synchronized(this) {
                if (file.length() > MAX_BYTES) file.writeText("")
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = buildString {
                    append(time).append("  ").append(what).append('\n')
                    if (withCaller) {
                        Throwable().stackTrace
                            .drop(1)
                            .filter { it.className.startsWith("com.spotiaisexs") && "PlaybackStopLog" !in it.className }
                            .take(CALLER_FRAMES)
                            .forEach { append("    at ").append(it).append('\n') }
                    }
                }
                file.appendText(text)
            }
        }
    }

    fun read(context: Context): String = runCatching {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) "(sin paradas registradas)" else file.readLines().takeLast(200).joinToString("\n")
    }.getOrDefault("(registro de paradas ilegible)")
}
