package com.teledrive.core.domain

import com.teledrive.core.model.MetadataOperation
import com.teledrive.core.model.MetadataRecord
import com.teledrive.core.model.MetadataRecordCodec
import com.teledrive.core.model.MetadataSnapshot
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class MetadataRecordFactory(
    private val deviceId: String,
    initialSequence: Long = 0,
    private val nowEpochSeconds: () -> Long,
    private val operationId: () -> String = { UUID.randomUUID().toString() },
) {
    private val sequence = AtomicLong(initialSequence)

    fun next(operation: MetadataOperation): MetadataRecord = MetadataRecord(
        operationId = operationId(),
        deviceId = deviceId,
        sequence = sequence.incrementAndGet(),
        timestampEpochSeconds = nowEpochSeconds(),
        operation = operation,
    )

    fun encodeNext(operation: MetadataOperation): String = MetadataRecordCodec.encode(next(operation))
}

data class MetadataSyncResult(
    val validRecords: Int,
    val malformedRecords: Int,
    val ignoredMessages: Int,
    val snapshot: MetadataSnapshot,
)

class MetadataSyncUseCase(
    private val gateway: TelegramStorageGateway,
) {
    suspend fun append(operation: MetadataOperation, factory: MetadataRecordFactory): com.teledrive.core.model.RemoteMessageId =
        gateway.sendMetadataRecord(factory.encodeNext(operation))

    suspend fun mergeMessages(messages: Iterable<String?>): MetadataSyncResult {
        val valid = mutableListOf<MetadataRecord>()
        var malformed = 0
        var ignored = 0
        messages.forEach { message ->
            when (val result = MetadataRecordCodec.decode(message)) {
                is com.teledrive.core.model.MetadataDecodeResult.Valid -> valid += result.record
                is com.teledrive.core.model.MetadataDecodeResult.Malformed -> malformed++
                com.teledrive.core.model.MetadataDecodeResult.NotMetadata -> ignored++
            }
        }
        return MetadataSyncResult(valid.size, malformed, ignored, com.teledrive.core.model.MetadataMerger.merge(valid))
    }
}
