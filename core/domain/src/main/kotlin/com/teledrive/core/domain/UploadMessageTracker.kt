package com.teledrive.core.domain

import com.teledrive.core.model.TransferId
/**
 * Correlates the provisional and final message ids emitted by TDLib while a document is sent.
 * It also allows an update to be matched before the sendMessage response has been resumed.
 */
class UploadMessageTracker {
    private data class Pending(
        val transferId: TransferId,
        val chatId: Long,
        val messageIds: MutableSet<Long> = linkedSetOf(),
    )

    private data class MessageKey(val chatId: Long, val messageId: Long)

    private val byTransfer = linkedMapOf<String, Pending>()
    private val byMessage = linkedMapOf<MessageKey, TransferId>()

    @Synchronized
    fun register(transferId: TransferId, chatId: Long, provisionalMessageId: Long? = null) {
        val pending = Pending(transferId, chatId)
        byTransfer[transferId.value] = pending
        provisionalMessageId?.takeIf { it > 0 }?.let { bindLocked(pending, it) }
    }

    @Synchronized
    fun bind(transferId: TransferId, messageId: Long?) {
        if (messageId == null || messageId <= 0) return
        byTransfer[transferId.value]?.let { bindLocked(it, messageId) }
    }

    /** Returns the pending transfer without completing it; repeated updates remain idempotently correlated. */
    @Synchronized
    fun resolve(chatId: Long, messageId: Long?, oldMessageId: Long? = null): TransferId? {
        val direct = oldMessageId?.takeIf { it > 0 }?.let { byMessage[MessageKey(chatId, it)] }
            ?: messageId?.takeIf { it > 0 }?.let { byMessage[MessageKey(chatId, it)] }
        if (direct != null) return direct
        val candidates = byTransfer.values.filter { it.chatId == chatId }
        if (candidates.size != 1) return null
        val pending = candidates.single()
        messageId?.takeIf { it > 0 }?.let { bindLocked(pending, it) }
        return pending.transferId
    }

    @Synchronized
    fun messageIds(transferId: TransferId): Set<Long> = byTransfer[transferId.value]?.messageIds?.toSet().orEmpty()

    @Synchronized
    fun complete(transferId: TransferId) {
        val pending = byTransfer.remove(transferId.value) ?: return
        pending.messageIds.forEach { byMessage.remove(MessageKey(pending.chatId, it)) }
    }

    @Synchronized
    fun remove(transferId: TransferId) = complete(transferId)

    private fun bindLocked(pending: Pending, messageId: Long) {
        pending.messageIds += messageId
        byMessage[MessageKey(pending.chatId, messageId)] = pending.transferId
    }
}
