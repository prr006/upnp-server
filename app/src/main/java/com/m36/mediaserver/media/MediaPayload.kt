package com.m36.mediaserver.media

import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** Read-only byte stream returned for a SAF media item. Offsets are relative to the asset slice. */
interface MediaPayload : Closeable {
    val stream: InputStream
    /** Length used to frame a media response; may fall back to the size advertised in DIDL. */
    val length: Long
    val startOffset: Long

    /** Media-relative size observed when opening the SAF descriptor, or null when unavailable. */
    val openedFileSize: Long?
        get() = length.takeIf { it >= 0 }

    /** Raw AssetFileDescriptor length, before any fallback to DIDL metadata. */
    val assetFileDescriptorLength: Long?
        get() = null

    /** Raw ParcelFileDescriptor stat size, which may include bytes before an AFD slice. */
    val descriptorStatSize: Long?
        get() = null

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
