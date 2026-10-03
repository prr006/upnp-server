package com.m36.mediaserver.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** An item visible under the one user-selected SAF tree. */
data class MediaNode(
    val objectId: String,
    val parentId: String,
    val documentId: String?,
    val title: String,
    val isContainer: Boolean,
    val mimeType: String,
    val size: Long,
    val modifiedMillis: Long,
    val mediaToken: String? = null,
)

/** A seekable SAF descriptor. Call close after streaming. */
class OpenedMedia internal constructor(
    private val descriptor: android.content.res.AssetFileDescriptor,
    val stream: FileInputStream,
    val length: Long,
    val startOffset: Long,
) : Closeable {
    @Throws(IOException::class)
    fun seek(relativePosition: Long) {
        require(relativePosition >= 0) { "Negative media offset" }
        stream.channel.position(startOffset + relativePosition)
    }

    override fun close() {
        runCatching { stream.close() }
        runCatching { descriptor.close() }
    }
}

/**
 * SAF-backed read-only media catalog. A document becomes addressable only after its containing
 * directory has been browsed, and the HTTP endpoint can open files only from that catalog.
 */
class DocumentTreeRepository(context: Context, val treeUri: Uri) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    private val directoryDocumentIds = ConcurrentHashMap<String, String>()
    private val knownNodes = ConcurrentHashMap<String, MediaNode>()
    private val knownFilesByToken = ConcurrentHashMap<String, MediaNode>()

    val rootTitle: String = queryDisplayName(documentUri(treeDocumentId)) ?: "Jellyfin"

    init {
        directoryDocumentIds[SHARED_ROOT_ID] = treeDocumentId
    }

    fun rootObject(): MediaNode = MediaNode(
        objectId = "0",
        parentId = "-1",
        documentId = null,
        title = "M36 Media Server",
        isContainer = true,
        mimeType = DIRECTORY_MIME,
        size = -1,
        modifiedMillis = 0,
    )

    fun sharedRootObject(): MediaNode = MediaNode(
        objectId = SHARED_ROOT_ID,
        parentId = "0",
        documentId = treeDocumentId,
        title = rootTitle,
        isContainer = true,
        mimeType = DIRECTORY_MIME,
        size = -1,
        modifiedMillis = 0,
    ).also { knownNodes[it.objectId] = it }

    @Throws(IOException::class)
    fun metadata(objectId: String): MediaNode? = when (objectId) {
        "0" -> rootObject()
        SHARED_ROOT_ID -> sharedRootObject()
        else -> knownNodes[objectId]
    }

    @Throws(IOException::class)
    fun children(parentObjectId: String): List<MediaNode> {
        if (parentObjectId == "0") return listOf(sharedRootObject())
        val parentDocumentId = directoryDocumentIds[parentObjectId]
            ?: throw FileNotFoundException("Unknown or unbrowsed UPnP container: $parentObjectId")
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<MediaNode>()
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("Storage provider returned no directory listing")
        cursor.use { c ->
            val idColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                throw IOException("Storage provider omitted required document metadata")
            }
            while (c.moveToNext()) {
                val documentId = c.getString(idColumn) ?: continue
                val title = c.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: documentId
                val mime = c.getString(mimeColumn) ?: "application/octet-stream"
                val container = mime == DIRECTORY_MIME
                val size = if (sizeColumn >= 0 && !c.isNull(sizeColumn)) c.getLong(sizeColumn) else -1L
                val modified = if (modifiedColumn >= 0 && !c.isNull(modifiedColumn)) c.getLong(modifiedColumn) else 0L
                val token = encodeDocumentId(documentId)
                val objectId = (if (container) "d:" else "i:") + token
                val node = MediaNode(
                    objectId = objectId,
                    parentId = parentObjectId,
                    documentId = documentId,
                    title = title,
                    isContainer = container,
                    mimeType = if (container) DIRECTORY_MIME else mime,
                    size = size,
                    modifiedMillis = modified,
                    mediaToken = if (container) null else token,
                )
                knownNodes[objectId] = node
                if (container) {
                    directoryDocumentIds[objectId] = documentId
                } else {
                    knownFilesByToken[token] = node
                }
                result += node
            }
        }
        return result.sortedWith(
            compareBy<MediaNode, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.title },
        )
    }

    @Throws(IOException::class)
    fun openMedia(token: String): OpenedMedia {
        val node = knownFilesByToken[token] ?: throw FileNotFoundException("Media item has not been browsed")
        val documentId = node.documentId ?: throw FileNotFoundException("Missing SAF document ID")
        val asset = resolver.openAssetFileDescriptor(documentUri(documentId), "r")
            ?: throw FileNotFoundException("Storage provider could not open ${node.title}")
        try {
            val descriptor = asset.parcelFileDescriptor
            val stream = FileInputStream(descriptor.fileDescriptor)
            val startOffset = asset.startOffset
            val length = when {
                asset.length >= 0 -> asset.length
                descriptor.statSize >= startOffset -> descriptor.statSize - startOffset
                node.size >= 0 -> node.size
                else -> -1L
            }
            return OpenedMedia(asset, stream, length, startOffset)
        } catch (error: Exception) {
            runCatching { asset.close() }
            throw if (error is IOException) error else IOException("Unable to open media descriptor", error)
        }
    }

    fun isKnownMedia(token: String): Boolean = knownFilesByToken.containsKey(token)

    fun metadataNodeForToken(token: String): MediaNode? = knownFilesByToken[token]

    fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun queryDisplayName(uri: Uri): String? = try {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } else null
        }
    } catch (_: Exception) {
        null
    }

    private fun encodeDocumentId(documentId: String): String =
        Base64.encodeToString(documentId.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        const val SHARED_ROOT_ID = "shared-root"
        const val DIRECTORY_MIME = "vnd.android.document/directory"
    }
}
