package com.m36.mediaserver.media

import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** Read-only byte stream returned for a SAF media item. Offsets are relative to the asset slice. */
interface MediaPayload : Closeable {
    val stream: InputStream
    val length: Long
    val startOffset: Long

    @Throws(IOException::class)
    fun seek(relativePosition: Long)
}

/** Position a SAF-backed FileInputStream at a byte offset relative to its AssetFileDescriptor. */
internal fun seekFileInputStream(
    stream: FileInputStream,
    assetStartOffset: Long,
    relativePosition: Long,
) {
    require(assetStartOffset >= 0 && relativePosition >= 0) { "Negative media offset" }
    stream.channel.position(assetStartOffset + relativePosition)
}
