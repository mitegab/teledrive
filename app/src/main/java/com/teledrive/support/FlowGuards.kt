package com.teledrive.support

import com.teledrive.core.model.TeleDriveLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Keeps a failing upstream flow (Room, DataStore, TDLib gateway) from killing the process.
 *
 * Screens collect these flows from `viewModelScope` — directly or through `stateIn` — where a
 * thrown exception has no handler and therefore reaches the thread's default handler. When the
 * database or preferences file fails to open, that used to crash whichever screen subscribed
 * first, which is why the failure looked unrelated to what the user was doing.
 *
 * The failure is logged with a redacted event name and the flow completes; the collector keeps
 * the last value it had (or [fallback] when one is supplied).
 */
fun <T> Flow<T>.guarded(tag: String, fallback: T? = null): Flow<T> = catch { error ->
    TeleDriveLog.d("flow.$tag.failed", "error" to error.message)
    val replacement = fallback
    if (replacement != null) emit(replacement)
}
