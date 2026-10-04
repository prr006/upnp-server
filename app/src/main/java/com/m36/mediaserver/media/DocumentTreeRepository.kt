package com.m36.mediaserver.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    val childCount: Int? = null,
)

/** Narrow catalog contract used by ContentDirectory and its JVM tests. */
interface MediaCatalog {
    val systemUpdateId: Long
    val lastEnumerationDiagnostics: String
    fun metadata(objectId: String): MediaNode?
    fun children(parentObjectId: String): List<MediaNode>
}

/** A seekable SAF descriptor. Call close after streaming. */
class OpenedMedia internal constructor(
    private val descriptor: android.content.res.AssetFileDescriptor,
    override val stream: FileInputStream,
    override val length: Long,
    override val startOffset: Long,
    override val openedFileSize: Long?,
    override val assetFileDescriptorLength: Long?,
    override val descriptorStatSize: Long?,
) : MediaPayload {
    @Throws(IOException::class)
    override fun seek(relativePosition: Long) {
        seekFileInputStream(stream, startOffset, relativePosition)
    }

    override fun close() {
        runCatching { stream.close() }
        runCatching { descriptor.close() }
    }
}

/**
 * SAF-backed read-only media catalog. Directory children are queried through the persisted tree
 * URI; containers and files receive stable IDs and parent IDs, and a file becomes HTTP-addressable
 * only after its containing directory has been enumerated.
 */
class DocumentTreeRepository(context: Context, val treeUri: Uri) : MediaCatalog {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    private val directoryDocumentIds = ConcurrentHashMap<String, String>()
    private val knownNodes = ConcurrentHashMap<String, MediaNode>()
    private val knownFilesByToken = ConcurrentHashMap<String, MediaNode>()
    private val directorySignatures = ConcurrentHashMap<String, List<DocumentSignature>>()
    private val updateSequence = AtomicLong(INITIAL_UPDATE_ID)

    @Volatile
    override var lastEnumerationDiagnostics: String = "SAF tree ready; no directory has been browsed yet"
        private set

    override val systemUpdateId: Long
        get() = updateSequence.get() and UPDATE_ID_MASK

    val rootTitle: String = queryDisplayName(documentUri(treeDocumentId)) ?: "Jellyfin"

    init {
        directoryDocumentIds[SHARED_ROOT_ID] = treeDocumentId
    }

    override fun metadata(objectId: String): MediaNode? = when (objectId) {
        "0" -> rootObject()
        SHARED_ROOT_ID -> sharedRootObject()
        else -> knownNodes[objectId]
    }

    @Throws(IOException::class)
    override fun children(parentObjectId: String): List<MediaNode> {
        if (parentObjectId == "0") {
            val sharedRoot = sharedRootObject()
            lastEnumerationDiagnostics =
                "SAF enumerated selected root '$rootTitle': entries=${sharedRoot.childCount ?: 0}; " +
                    "returned the shared-root container id=$SHARED_ROOT_ID"
            return listOf(sharedRoot)
        }

        val parentDocumentId = directoryDocumentIds[parentObjectId]
            ?: throw FileNotFoundException("Unknown or unbrowsed UPnP container: $parentObjectId")
        val parentTitle = knownNodes[parentObjectId]?.title ?: rootTitle

        try {
            val documents = queryChildren(parentDocumentId)
            var descendantDirectoriesCounted = 0
            val childCountFailures = ArrayList<String>()
            val result = ArrayList<MediaNode>(documents.size)
            documents.forEach { document ->
                val container = document.mimeType == DIRECTORY_MIME
                val token = encodeDocumentId(document.documentId)
                val objectId = (if (container) "d:" else "i:") + token
                // childCount is computed from the provider's actual child-document listing, rather
                // than guessed from a MIME type or omitted (some DLNA clients treat that as empty).
                // A failed count-only scan should not prevent clients from entering the folder.
                val childCount = if (container) {
                    descendantDirectoriesCounted++
                    try {
                        queryChildren(document.documentId).size
                    } catch (error: Exception) {
                        childCountFailures += "${document.displayName}: ${error.message ?: error.javaClass.simpleName}"
                        null
                    }
                } else {
                    null
                }
                val node = MediaNode(
                    objectId = objectId,
                    parentId = parentObjectId,
                    documentId = document.documentId,
                    title = document.displayName,
                    isContainer = container,
                    mimeType = if (container) DIRECTORY_MIME else document.mimeType,
                    size = document.size,
                    modifiedMillis = document.modifiedMillis,
                    mediaToken = if (container) null else token,
                    childCount = childCount,
                )
                knownNodes[objectId] = node
                if (container) {
                    directoryDocumentIds[objectId] = document.documentId
                } else {
                    knownFilesByToken[token] = node
                }
                result += node
            }
            val directoryCount = result.count { it.isContainer }
            val fileCount = result.size - directoryCount
            lastEnumerationDiagnostics = buildString {
                append("SAF enumerated '$parentTitle' (ObjectID=$parentObjectId): ")
                append("entries=${result.size}, directories=$directoryCount, files=$fileCount, ")
                append("child-count directory scans=$descendantDirectoriesCounted")
                if (childCountFailures.isNotEmpty()) {
                    append("; child-count scan failures=${childCountFailures.joinToString()}")
                }
            }
            return result.sortedWith(
                compareBy<MediaNode, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.title },
            )
        } catch (error: Exception) {
            lastEnumerationDiagnostics =
                "SAF enumeration failed for '$parentTitle' (ObjectID=$parentObjectId): " +
                    (error.message ?: error.javaClass.simpleName)
            if (error is IOException) throw error
            throw IOException("Unable to enumerate selected SAF directory '$parentTitle'", error)
        }
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
            val descriptorStatSize = descriptor.statSize.takeIf { it >= 0 }
            val assetLength = asset.length.takeIf { it >= 0 }
            val openedFileSize = assetLength ?: descriptorStatSize
                ?.takeIf { it >= startOffset }
                ?.minus(startOffset)
            val length = openedFileSize ?: node.size.takeIf { it >= 0 } ?: -1L
            return OpenedMedia(
                descriptor = asset,
                stream = stream,
                length = length,
                startOffset = startOffset,
                openedFileSize = openedFileSize,
                assetFileDescriptorLength = assetLength,
                descriptorStatSize = descriptorStatSize,
            )
        } catch (error: Exception) {
            runCatching { asset.close() }
            throw if (error is IOException) error else IOException("Unable to open media descriptor", error)
        }
    }

    fun isKnownMedia(token: String): Boolean = knownFilesByToken.containsKey(token)

    fun metadataNodeForToken(token: String): MediaNode? = knownFilesByToken[token]

    fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun rootObject(): MediaNode = MediaNode(
        objectId = "0",
        parentId = "-1",
        documentId = null,
        title = "M36 Media Server",
        isContainer = true,
        mimeType = DIRECTORY_MIME,
        size = -1,
        modifiedMillis = 0,
        childCount = 1,
    )

    private fun sharedRootObject(): MediaNode {
        try {
            val directChildren = queryChildren(treeDocumentId)
            val node = MediaNode(
                objectId = SHARED_ROOT_ID,
                parentId = "0",
                documentId = treeDocumentId,
                title = rootTitle,
                isContainer = true,
                mimeType = DIRECTORY_MIME,
                size = -1,
                modifiedMillis = 0,
                childCount = directChildren.size,
            )
            knownNodes[node.objectId] = node
            lastEnumerationDiagnostics =
                "SAF enumerated selected root '$rootTitle': entries=${directChildren.size}, " +
                    "directories=${directChildren.count { it.mimeType == DIRECTORY_MIME }}, " +
                    "files=${directChildren.count { it.mimeType != DIRECTORY_MIME }}"
            return node
        } catch (error: Exception) {
            lastEnumerationDiagnostics =
                "SAF enumeration failed for selected root '$rootTitle': ${error.message ?: error.javaClass.simpleName}"
            throw error
        }
    }

    private data class SafDocument(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val modifiedMillis: Long,
    )

    private data class DocumentSignature(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val modifiedMillis: Long,
    )

    private fun queryChildren(parentDocumentId: String): List<SafDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("Storage provider returned no directory listing for '$rootTitle'")
        val documents = ArrayList<SafDocument>()
        cursor.use { c ->
            val idColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idColumn < 0) throw IOException("Storage provider omitted document IDs")
            while (c.moveToNext()) {
                val documentId = c.getString(idColumn) ?: continue
                val displayName = if (nameColumn >= 0) {
                    c.getString(nameColumn)?.takeIf { it.isNotBlank() }
                } else null
                val mimeType = if (mimeColumn >= 0) c.getString(mimeColumn) else null
                val size = if (sizeColumn >= 0 && !c.isNull(sizeColumn)) c.getLong(sizeColumn) else -1L
                val modified = if (modifiedColumn >= 0 && !c.isNull(modifiedColumn)) c.getLong(modifiedColumn) else 0L
                documents += SafDocument(
                    documentId = documentId,
                    displayName = displayName ?: documentId.substringAfterLast('/').ifBlank { documentId },
                    mimeType = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                    size = size,
                    modifiedMillis = modified,
                )
            }
        }
        recordDirectoryListing(parentDocumentId, documents)
        return documents
    }

    private fun recordDirectoryListing(parentDocumentId: String, documents: List<SafDocument>) {
        val signature = documents.map {
            DocumentSignature(it.documentId, it.displayName, it.mimeType, it.size, it.modifiedMillis)
        }.sortedBy { it.documentId }
        val previous = directorySignatures.put(parentDocumentId, signature)
        if (previous != null && previous != signature) {
            updateSequence.updateAndGet { (it + 1L) and UPDATE_ID_MASK }
        }
    }

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
        private const val INITIAL_UPDATE_ID = 1L
        private const val UPDATE_ID_MASK = 0xFFFF_FFFFL
    }
}
