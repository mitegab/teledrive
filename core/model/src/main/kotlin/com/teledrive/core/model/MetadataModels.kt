package com.teledrive.core.model

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MetadataRecord(
    val schema: Int = 1,
    val operationId: String,
    val deviceId: String,
    val sequence: Long,
    val timestampEpochSeconds: Long,
    val operation: MetadataOperation,
)

@Serializable
sealed interface MetadataOperation {
    @Serializable
    @SerialName("create_folder")
    data class CreateFolder(val folderId: String, val name: String, val parentId: String? = null) : MetadataOperation

    @Serializable
    @SerialName("rename_folder")
    data class RenameFolder(val folderId: String, val name: String) : MetadataOperation

    @Serializable
    @SerialName("delete_folder")
    data class DeleteFolder(val folderId: String) : MetadataOperation

    @Serializable
    @SerialName("move_file")
    data class MoveFile(val fileId: String, val folderId: String? = null) : MetadataOperation

    @Serializable
    @SerialName("favorite_file")
    data class FavoriteFile(val fileId: String, val favorite: Boolean) : MetadataOperation
}

sealed interface MetadataDecodeResult {
    data object NotMetadata : MetadataDecodeResult
    data class Valid(val record: MetadataRecord) : MetadataDecodeResult
    data class Malformed(val reason: String) : MetadataDecodeResult
}

/** Reserved text-message envelope. It is deliberately not a Telegram username or filename marker. */
object MetadataRecordCodec {
    const val PREFIX = "\u2063TELEDRIVE_METADATA_V1:"
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encode(record: MetadataRecord): String {
        require(record.schema == 1) { "Unsupported metadata schema ${record.schema}" }
        val payload = json.encodeToString(MetadataRecord.serializer(), record).encodeToByteArray()
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        return PREFIX + encoded
    }

    fun decode(messageText: String?): MetadataDecodeResult {
        if (messageText == null || !messageText.startsWith(PREFIX)) return MetadataDecodeResult.NotMetadata
        val encoded = messageText.removePrefix(PREFIX)
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            val record = json.decodeFromString(MetadataRecord.serializer(), bytes.decodeToString())
            require(record.schema == 1) { "Unsupported metadata schema ${record.schema}" }
            require(record.operationId.isNotBlank()) { "Missing operation id" }
            require(record.deviceId.isNotBlank()) { "Missing device id" }
            require(record.sequence >= 0) { "Negative sequence" }
            MetadataDecodeResult.Valid(record)
        }.getOrElse { error -> MetadataDecodeResult.Malformed(error.message ?: "Invalid metadata") }
    }
}

data class MetadataSnapshot(
    val folders: Map<String, Folder> = emptyMap(),
    val fileFolderIds: Map<String, String?> = emptyMap(),
    val favoriteFileIds: Set<String> = emptySet(),
    val favoriteFileStates: Map<String, Boolean> = emptyMap(),
    val deletedFolderIds: Set<String> = emptySet(),
    val appliedOperationIds: Set<String> = emptySet(),
)

object MetadataMerger {
    fun merge(records: Iterable<MetadataRecord>, nowEpochSeconds: Long = 0L): MetadataSnapshot {
        val folders = linkedMapOf<String, Folder>()
        val fileFolders = linkedMapOf<String, String?>()
        val favorites = linkedSetOf<String>()
        val favoriteStates = linkedMapOf<String, Boolean>()
        val deletedFolders = linkedSetOf<String>()
        val applied = linkedSetOf<String>()
        val ordered = records
            .filter { it.schema == 1 }
            .distinctBy { it.operationId }
            .sortedWith(compareBy<MetadataRecord> { it.sequence }.thenBy { it.deviceId }.thenBy { it.operationId })

        ordered.forEach { record ->
            if (!applied.add(record.operationId)) return@forEach
            val timestamp = if (record.timestampEpochSeconds > 0) record.timestampEpochSeconds else nowEpochSeconds
            when (val operation = record.operation) {
                is MetadataOperation.CreateFolder -> {
                    deletedFolders.remove(operation.folderId)
                    folders[operation.folderId] = Folder(
                        id = operation.folderId,
                        name = operation.name,
                        parentId = operation.parentId,
                        createdAtEpochSeconds = timestamp,
                        modifiedAtEpochSeconds = timestamp,
                    )
                }
                is MetadataOperation.RenameFolder -> folders[operation.folderId]?.let { existing ->
                    folders[operation.folderId] = existing.copy(name = operation.name, modifiedAtEpochSeconds = timestamp)
                }
                is MetadataOperation.DeleteFolder -> {
                    deletedFolders.add(operation.folderId)
                    folders.remove(operation.folderId)
                    fileFolders.entries.filter { it.value == operation.folderId }.forEach { entry ->
                        fileFolders[entry.key] = null
                    }
                }
                is MetadataOperation.MoveFile -> {
                    fileFolders[operation.fileId] = operation.folderId?.takeIf(folders::containsKey)
                }
                is MetadataOperation.FavoriteFile -> {
                    favoriteStates[operation.fileId] = operation.favorite
                    if (operation.favorite) favorites.add(operation.fileId) else favorites.remove(operation.fileId)
                }
            }
        }
        return MetadataSnapshot(folders, fileFolders, favorites, favoriteStates, deletedFolders, applied)
    }
}
