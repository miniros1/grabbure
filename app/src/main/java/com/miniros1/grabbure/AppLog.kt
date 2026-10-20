package com.miniros1.grabbure

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun line(message: String) {
        synchronized(lines) {
            lines.addLast(clock.format(Date()) + " " + message)
            while (lines.size > 200) lines.removeFirst()
        }
    }

    fun appendNative(block: String) {
        if (block.isBlank()) return
        synchronized(lines) {
            for (raw in block.split('\n')) {
                val line = raw.trimEnd()
                if (line.isNotEmpty()) lines.addLast(line)
            }
            while (lines.size > 200) lines.removeFirst()
        }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
}
