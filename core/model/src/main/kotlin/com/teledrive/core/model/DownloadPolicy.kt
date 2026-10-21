package com.teledrive.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tunables for the offline download pipeline. Kept in the pure model layer so the
 * storage, concurrency and progress rules can be tested without Android.
 */
data class DownloadLimits(
    val maxConcurrentTransfers: Int = 3,
    val maxAutomaticAttempts: Int = TransferRetryPolicy.MAX_AUTOMATIC_ATTEMPTS,
    /** Minimum wall-clock spacing between persisted progress updates. */
    val progressPersistIntervalMillis: Long = 1_000L,
    /** A transfer that advanced this much since the last write is persisted immediately. */
    val progressPersistMinDeltaBytes: Long = 512L * 1024L,
    /** Window used to smooth transfer speed. */
    val speedEstimateWindowMillis: Long = 3_000L,
    /** Free space kept available in addition to the download itself. */
    val baseFreeSpaceMarginBytes: Long = 32L * 1024L * 1024L,
    /** Additional safety margin proportional to the file size. */
    val freeSpaceMarginFraction: Double = 0.02,
) {
    init {
        require(maxConcurrentTransfers in 1..8) { "Bounded concurrency must stay between 1 and 8 transfers" }
        require(maxAutomaticAttempts in 1..10) { "Automatic attempts must stay bounded" }
        require(progressPersistIntervalMillis >= 0L)
        require(progressPersistMinDeltaBytes >= 0L)
        require(speedEstimateWindowMillis >= 1_000L)
        require(baseFreeSpaceMarginBytes >= 0L)
        require(freeSpaceMarginFraction in 0.0..0.5)
    }

    /** Bytes that must be free before a download of [fileSizeBytes] may start. */
    fun requiredFreeBytes(fileSizeBytes: Long?): Long {
        val size = (fileSizeBytes ?: 0L).coerceAtLeast(0L)
        val proportional = (size.toDouble() * freeSpaceMarginFraction).toLong()
        return size + max(baseFreeSpaceMarginBytes, proportional)
    }

    fun hasEnoughSpace(freeBytes: Long, fileSizeBytes: Long?): Boolean =
        freeBytes >= 0L && freeBytes >= requiredFreeBytes(fileSizeBytes)

    companion object {
        val Default = DownloadLimits()
    }
}

/** Smoothed transfer measurements exposed to the UI. */
data class TransferSpeedSnapshot(
    val bytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    /** True only when the rate is measured over enough time to show a precise ETA. */
    val isStable: Boolean = false,
    val elapsedMillis: Long = 0L,
    val downloadedBytes: Long = 0L,
) {
    val speedLabelBytesPerSecond: Long? get() = bytesPerSecond?.takeIf { it > 0L }
}

/**
 * Sliding-window speed estimator with exponential smoothing.
 *
 * Deterministic: callers pass the timestamp, so tests never depend on wall time.
 * Returns `null` speed until at least two meaningful samples exist, and an ETA only
 * when the total size is known, the transfer is stable, and the rate is positive.
 */
class TransferSpeedEstimator(
    private val windowMillis: Long = 3_000L,
    private val minimumSpanMillis: Long = 1_000L,
    private val emaAlpha: Double = 0.35,
) {
    private data class Sample(val atMillis: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var smoothedRate: Double? = null
    private var startedAtMillis: Long? = null
    private var lastBytes: Long = 0L

    fun reset() {
        samples.clear()
        smoothedRate = null
        startedAtMillis = null
        lastBytes = 0L
    }

    fun sample(bytesTransferred: Long, nowMillis: Long): TransferSpeedSnapshot {
        val bytes = bytesTransferred.coerceAtLeast(0L)
        if (bytes < lastBytes) reset()
        lastBytes = bytes
        if (startedAtMillis == null) startedAtMillis = nowMillis
        samples.addLast(Sample(nowMillis, bytes))
        while (samples.size > 2 && nowMillis - samples.first().atMillis > windowMillis) samples.removeFirst()
        return snapshot(bytes, nowMillis, totalBytes = null)
    }

    fun snapshot(totalBytes: Long?, nowMillis: Long): TransferSpeedSnapshot =
        snapshot(lastBytes, nowMillis, totalBytes)

    private fun snapshot(bytes: Long, nowMillis: Long, totalBytes: Long?): TransferSpeedSnapshot {
        val startedAt = startedAtMillis ?: nowMillis
        val elapsed = (nowMillis - startedAt).coerceAtLeast(0L)
        val first = samples.firstOrNull()
        val spanMillis = if (first == null) 0L else (nowMillis - first.atMillis).coerceAtLeast(0L)
        if (first == null || samples.size < 2 || spanMillis < minimumSpanMillis) {
            return TransferSpeedSnapshot(elapsedMillis = elapsed, downloadedBytes = bytes)
        }
        val windowedRate = (bytes - first.bytes).toDouble() / (spanMillis / 1000.0)
        val previous = smoothedRate
        val smoothed = if (previous == null) windowedRate else previous + emaAlpha * (windowedRate - previous)
        smoothedRate = smoothed
        if (smoothed <= 0.0) {
            return TransferSpeedSnapshot(elapsedMillis = elapsed, downloadedBytes = bytes)
        }

        // Stability: the last four intervals must agree within 35% of the smoothed rate.
        val recent = samples.takeLast(4)
        val rates = recent.zipWithNext { a, b ->
            val deltaMs = (b.atMillis - a.atMillis).coerceAtLeast(1L)
            (b.bytes - a.bytes).toDouble() / (deltaMs / 1000.0)
        }
        val stable = rates.isNotEmpty() && rates.all { abs(it - smoothed) <= smoothed * 0.35 + 1.0 }
        val remaining = totalBytes?.takeIf { it > bytes }?.minus(bytes)
        val eta = remaining?.takeIf { stable }?.let { (it / smoothed).toLong().coerceAtLeast(0L) }
        return TransferSpeedSnapshot(
            bytesPerSecond = smoothed.toLong().takeIf { it > 0L },
            etaSeconds = eta,
            isStable = stable,
            elapsedMillis = elapsed,
            downloadedBytes = bytes,
        )
    }
}

/** Throttling rules that keep Room writes and recomposition out of the per-update path. */
object TransferProgressPolicy {
    /** Room persistence: interval OR a meaningful byte delta, always on terminal states. */
    fun shouldPersistProgress(
        lastPersistedAtMillis: Long?,
        lastPersistedBytes: Long,
        bytesTransferred: Long,
        state: TransferState,
        nowMillis: Long,
        limits: DownloadLimits = DownloadLimits.Default,
    ): Boolean {
        if (state.isTerminal) return true
        val lastAt = lastPersistedAtMillis ?: return true
        if (nowMillis - lastAt >= limits.progressPersistIntervalMillis) return true
        return bytesTransferred - lastPersistedBytes >= limits.progressPersistMinDeltaBytes
    }

    /** UI snapshots are refreshed more often than the database but still rate-limited. */
    fun shouldRefreshUi(lastEmittedAtMillis: Long?, nowMillis: Long, minimumIntervalMillis: Long = 250L): Boolean {
        val last = lastEmittedAtMillis ?: return true
        return nowMillis - last >= minimumIntervalMillis
    }

    /** Percentage used for labels; never hides a started transfer behind "0%". */
    fun progressPercent(bytesTransferred: Long, totalBytes: Long?): Int? =
        totalBytes?.takeIf { it > 0L }?.let { total ->
            ((bytesTransferred.coerceIn(0L, total) * 100L) / total).toInt().coerceIn(0, 100)
        }

    fun remainingBytes(bytesTransferred: Long, totalBytes: Long?): Long? =
        totalBytes?.takeIf { it > bytesTransferred }?.minus(bytesTransferred)

    /** A transfer that produced no progress for too long is treated as stalled by callers. */
    fun isStalled(lastProgressAtMillis: Long?, nowMillis: Long, timeoutMillis: Long = 120_000L): Boolean {
        val last = lastProgressAtMillis ?: return false
        return nowMillis - last >= timeoutMillis
    }

    fun humanizeSpeed(bytesPerSecond: Long?): String? {
        val value = bytesPerSecond?.takeIf { it > 0L } ?: return null
        val kilo = 1024.0
        return when {
            value < kilo -> "$value B/s"
            value < kilo * kilo -> "${(value / kilo).toLong()} KB/s"
            else -> String.format(java.util.Locale.US, "%.1f MB/s", value / (kilo * kilo))
        }
    }

    fun humanizeEta(seconds: Long?): String? {
        val value = seconds?.takeIf { it > 0L } ?: return null
        val minutes = value / 60
        return when {
            value < 60 -> "${value}s left"
            minutes < 60 -> "${minutes}m left"
            else -> "${minutes / 60}h ${minutes % 60}m left"
        }
    }

    fun humanizeBytes(bytes: Long?): String = when {
        bytes == null || bytes < 0L -> "size unknown"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        else -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /** Clamped progress in [0,1] for Compose indicators. */
    fun fraction(bytesTransferred: Long, totalBytes: Long?): Float? =
        progressPercent(bytesTransferred, totalBytes)?.let { it / 100f }

    fun clampProgress(fraction: Float?): Float = when {
        fraction == null -> 0f
        fraction.isNaN() -> 0f
        else -> min(1f, max(0f, fraction))
    }
}
