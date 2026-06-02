package com.example.mobile_assistant

import android.util.Log

internal data class MemorySaveEvent(
    val path: String,
    val summary: String,
    val timestampMs: Long
)

internal class MemorySaveLog(private val capacity: Int = 20) {
    private val lock = Any()
    private val events: ArrayDeque<MemorySaveEvent> = ArrayDeque(capacity)

    fun record(path: String, summary: String, timestampMs: Long = System.currentTimeMillis()) {
        val event = MemorySaveEvent(path = path, summary = summary, timestampMs = timestampMs)
        synchronized(lock) {
            if (events.size >= capacity) events.removeFirst()
            events.addLast(event)
        }
        runCatching { Log.i(TAG, "Saved $path — $summary") }
    }

    fun snapshot(): List<MemorySaveEvent> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { events.clear() }

    private companion object {
        private const val TAG = "Memory"
    }
}
