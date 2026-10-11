package com.teledrive.metadata

import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.model.HistoryCursor
import com.teledrive.core.model.MetadataDecodeResult
import com.teledrive.core.model.MetadataMerger
import com.teledrive.core.model.MetadataOperation
import com.teledrive.core.model.MetadataRecord
import com.teledrive.core.model.MetadataRecordCodec
import com.teledrive.core.model.MetadataSnapshot
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.preferences.PreferencesRepository
import dagger.hilt.android.scopes.ViewModelScoped
import java.util.UUID
import javax.inject.Inject

@ViewModelScoped
class MetadataSyncWriter @Inject constructor(
    private val gateway: TelegramStorageGateway,
    private val preferences: PreferencesRepository,
) {
    suspend fun append(operation: MetadataOperation): RemoteMessageId {
        val record = MetadataRecord(
            operationId = UUID.randomUUID().toString(),
            deviceId = preferences.getOrCreateDeviceId(),
            sequence = preferences.nextMetadataSequence(),
            timestampEpochSeconds = System.currentTimeMillis() / 1000,
            operation = operation,
        )
        return gateway.sendMetadataRecord(MetadataRecordCodec.encode(record))
    }

    suspend fun synchronize(limit: Int = 100): MetadataSyncResult {
        var cursor = HistoryCursor()
        val records = mutableListOf<MetadataRecord>()
        var malformed = 0
        var pages = 0
        while (true) {
            val page = gateway.getMetadataMessagesPage(cursor, limit.coerceIn(1, 100))
            pages++
            page.records.forEach { text ->
                when (val decoded = MetadataRecordCodec.decode(text)) {
                    is MetadataDecodeResult.Valid -> records += decoded.record
                    is MetadataDecodeResult.Malformed -> malformed++
                    MetadataDecodeResult.NotMetadata -> Unit
                }
            }
            val next = page.nextCursor ?: break
            if (next == cursor) break
            cursor = next
        }
        return MetadataSyncResult(
            pages = pages,
            validRecords = records.distinctBy { it.operationId }.size,
            malformedRecords = malformed,
            snapshot = MetadataMerger.merge(records),
        )
    }
}

data class MetadataSyncResult(
    val pages: Int,
    val validRecords: Int,
    val malformedRecords: Int,
    val snapshot: MetadataSnapshot,
)
