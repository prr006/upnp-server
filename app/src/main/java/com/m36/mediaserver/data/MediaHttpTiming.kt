package com.m36.mediaserver.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Wall-clock label plus a monotonic clock value used for reliable per-request durations. */
data class MediaHttpInstant(
    val epochMillis: Long,
    val monotonicNanos: Long,
) {
    fun formatLocal(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        .format(Date(epochMillis))

    companion object {
        fun now(): MediaHttpInstant = MediaHttpInstant(
            epochMillis = System.currentTimeMillis(),
            monotonicNanos = System.nanoTime(),
        )
    }
}

/** Mutable event marks are owned by one HTTP worker; snapshots are safe to publish to the UI. */
class MediaHttpTimeline(
    val requestReceived: MediaHttpInstant = MediaHttpInstant.now(),
) {
    @Volatile var safOpenStarted: MediaHttpInstant? = null
    @Volatile var safOpenCompleted: MediaHttpInstant? = null
    @Volatile var safSeekStarted: MediaHttpInstant? = null
    @Volatile var safSeekCompleted: MediaHttpInstant? = null
    @Volatile var headersSent: MediaHttpInstant? = null
    @Volatile var firstMediaByte: MediaHttpInstant? = null
    @Volatile var lastMediaByte: MediaHttpInstant? = null
    @Volatile var clientDisconnected: MediaHttpInstant? = null
    @Volatile var completed: MediaHttpInstant? = null
    @Volatile var bytesReadFromSaf: Long = 0
    @Volatile var bytesWrittenToResponse: Long = 0

    @Synchronized
    fun snapshot(): MediaHttpTimingSnapshot = MediaHttpTimingSnapshot(
        requestReceived = requestReceived,
        safOpenStarted = safOpenStarted,
        safOpenCompleted = safOpenCompleted,
        safSeekStarted = safSeekStarted,
        safSeekCompleted = safSeekCompleted,
        headersSent = headersSent,
        firstMediaByte = firstMediaByte,
        lastMediaByte = lastMediaByte,
        clientDisconnected = clientDisconnected,
        completed = completed,
    )
}

data class MediaHttpTimingSnapshot(
    val requestReceived: MediaHttpInstant,
    val safOpenStarted: MediaHttpInstant? = null,
    val safOpenCompleted: MediaHttpInstant? = null,
    val safSeekStarted: MediaHttpInstant? = null,
    val safSeekCompleted: MediaHttpInstant? = null,
    val headersSent: MediaHttpInstant? = null,
    val firstMediaByte: MediaHttpInstant? = null,
    val lastMediaByte: MediaHttpInstant? = null,
    val clientDisconnected: MediaHttpInstant? = null,
    val completed: MediaHttpInstant? = null,
) {
    fun elapsedMillis(mark: MediaHttpInstant?): Long? = mark?.let {
        nonNegativeMillis(it.monotonicNanos - requestReceived.monotonicNanos)
    }

    fun durationMillis(start: MediaHttpInstant?, end: MediaHttpInstant?): Long? {
        if (start == null || end == null) return null
        return nonNegativeMillis(end.monotonicNanos - start.monotonicNanos)
    }

    fun totalDurationMillis(): Long? = durationMillis(requestReceived, completed)

    private fun nonNegativeMillis(nanos: Long): Long = (nanos.coerceAtLeast(0L) / NANOS_PER_MILLISECOND)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
