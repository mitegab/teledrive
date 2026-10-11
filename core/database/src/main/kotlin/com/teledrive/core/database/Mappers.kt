package com.teledrive.core.database

import com.teledrive.core.model.Folder
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.MediaType
import com.teledrive.core.model.RemoteAvailability
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.Transfer
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferId
import com.teledrive.core.model.TransferState

fun StoredFileEntity.toDomain(): StoredFile = StoredFile(
    remote = RemoteMessageId(remoteChatId, remoteMessageId),
    tdlibFileId = tdlibFileId,
    originalFilename = originalFilename,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    savedAtEpochSeconds = savedAtEpochSeconds,
    caption = caption,
    folderId = folderId,
    favorite = favorite,
    remoteAvailability = runCatching { RemoteAvailability.valueOf(remoteAvailability) }.getOrDefault(RemoteAvailability.UNKNOWN),
    localAvailability = runCatching { LocalAvailability.valueOf(localAvailability) }.getOrDefault(LocalAvailability.NOT_DOWNLOADED),
    localPath = localPath,
    mediaType = runCatching { MediaType.valueOf(mediaType) }.getOrDefault(MediaType.UNKNOWN),
    remoteUniqueId = remoteUniqueId,
    thumbnailFileId = thumbnailFileId,
    durationSeconds = durationSeconds,
    localSizeBytes = localSizeBytes,
)

fun StoredFile.toEntity(existing: StoredFileEntity? = null): StoredFileEntity = StoredFileEntity(
    stableId = stableId,
    remoteChatId = remote.chatId,
    remoteMessageId = remote.messageId,
    tdlibFileId = tdlibFileId ?: existing?.tdlibFileId,
    originalFilename = originalFilename,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    savedAtEpochSeconds = savedAtEpochSeconds,
    caption = caption,
    // Remote history refreshes must never erase local organization state.
    folderId = folderId ?: existing?.folderId,
    favorite = favorite || existing?.favorite == true,
    remoteAvailability = remoteAvailability.name,
    localAvailability = if (localAvailability == LocalAvailability.NOT_DOWNLOADED && existing != null) {
        runCatching { LocalAvailability.valueOf(existing.localAvailability) }.getOrDefault(localAvailability).name
    } else localAvailability.name,
    localPath = localPath ?: existing?.localPath,
    mediaType = mediaType.takeIf { it != MediaType.UNKNOWN }?.name ?: existing?.mediaType ?: MediaType.UNKNOWN.name,
    remoteUniqueId = remoteUniqueId ?: existing?.remoteUniqueId,
    thumbnailFileId = thumbnailFileId ?: existing?.thumbnailFileId,
    durationSeconds = durationSeconds ?: existing?.durationSeconds,
    localSizeBytes = localSizeBytes ?: existing?.localSizeBytes,
)

fun FolderEntity.toDomain(): Folder = Folder(id, name, parentId, createdAtEpochSeconds, modifiedAtEpochSeconds)
fun Folder.toEntity(): FolderEntity = FolderEntity(id, name, parentId, createdAtEpochSeconds, modifiedAtEpochSeconds)

fun TransferEntity.toDomain(): Transfer = Transfer(
    id = TransferId(id),
    direction = runCatching { TransferDirection.valueOf(direction) }.getOrDefault(TransferDirection.DOWNLOAD),
    filename = filename,
    sourcePath = sourcePath,
    remote = if (remoteChatId != null && remoteMessageId != null) RemoteMessageId(remoteChatId, remoteMessageId) else null,
    tdlibFileId = tdlibFileId,
    bytesTransferred = bytesTransferred,
    totalBytes = totalBytes,
    state = runCatching { TransferState.valueOf(state) }.getOrDefault(TransferState.FAILED),
    errorMessage = errorMessage,
    attempt = attempt,
    retryAtEpochSeconds = retryAtEpochSeconds,
)

fun Transfer.toEntity(): TransferEntity = TransferEntity(
    id = id.value,
    direction = direction.name,
    filename = filename,
    sourcePath = sourcePath,
    remoteChatId = remote?.chatId,
    remoteMessageId = remote?.messageId,
    tdlibFileId = tdlibFileId,
    bytesTransferred = bytesTransferred,
    totalBytes = totalBytes,
    state = state.name,
    errorMessage = errorMessage,
    attempt = attempt,
    retryAtEpochSeconds = retryAtEpochSeconds,
)
