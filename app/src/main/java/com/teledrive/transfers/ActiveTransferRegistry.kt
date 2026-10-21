package com.teledrive.transfers

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Prevents a WorkManager drain in the current process from duplicating a foreground transfer. */
@Singleton
class ActiveTransferRegistry @Inject constructor() {
    private val active = ConcurrentHashMap.newKeySet<String>()

    fun markActive(id: String) { active += id }
    fun clear(id: String) { active -= id }
    fun isActive(id: String): Boolean = id in active

    /** Snapshot of in-process transfer ids; used to avoid settling a live transfer at startup. */
    fun activeIds(): Set<String> = active.toSet()
}
