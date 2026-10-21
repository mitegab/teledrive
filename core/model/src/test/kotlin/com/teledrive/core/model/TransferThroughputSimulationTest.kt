package com.teledrive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic policy simulations for the numbers reported in the final summary.
 *
 * These are NOT device measurements: no TDLib client, network or disk is involved.
 * They replay TDLib-like update ticks through the same policy objects the app uses,
 * so the *relative* effect of throttling and worker bounds can be stated without
 * claiming a real transfer speed. Any absolute speed claim must come from a device.
 */
class TransferThroughputSimulationTest {

    /** A 50 MB transfer reporting progress every 100 ms for two minutes. */
    @Test
    fun throttledPersistenceCutsDatabaseWritesWithoutLosingCompletion() {
        val totalBytes = 50L * 1024L * 1024L
        val ticks = 1_200
        val limits = DownloadLimits.Default
        val bytesPerTick = totalBytes / ticks

        var naiveWrites = 0
        for (tick in 1..ticks) {
            // Simulates the pre-refactor behaviour: one write per TDLib update.
            if (tick * bytesPerTick > 0) naiveWrites++
        }

        var writes = 0
        var lastAt: Long? = null
        var lastBytes = 0L
        for (tick in 1..ticks) {
            val now = tick * 100L
            val bytes = tick * bytesPerTick
            if (TransferProgressPolicy.shouldPersistProgress(lastAt, lastBytes, bytes, TransferState.RUNNING, now, limits)) {
                writes++
                lastAt = now
                lastBytes = bytes
            }
        }
        assertTrue(TransferProgressPolicy.shouldPersistProgress(lastAt, lastBytes, totalBytes, TransferState.COMPLETED, ticks * 100L, limits))

        println("persistence: naive=$naiveWrites throttled=$writes ticks=$ticks")
        assertTrue("throttling should cut writes by at least 8x", naiveWrites >= writes * 8)
        assertTrue("completion must still be written", writes >= 1)
    }

    /** Replays the coordinator queue with N equal-duration downloads. */
    @Test
    fun boundedWorkerPoolIsFasterThanSerialWithoutUnboundedConcurrency() {
        val downloadMillis = 4_000
        fun makespan(downloads: Int, workers: Int): Long {
            val slots = LongArray(workers)
            repeat(downloads) { index ->
                var earliest = 0
                for (worker in slots.indices) if (slots[worker] < slots[earliest]) earliest = worker
                slots[earliest] += downloadMillis
            }
            return slots.max()
        }

        val workers = DownloadLimits.Default.maxConcurrentTransfers
        val serial = makespan(downloads = 6, workers = 1)
        val bounded = makespan(downloads = 6, workers = workers)
        println("scheduling: downloads=6 workers=$workers serial=${serial}ms bounded=${bounded}ms")
        assertEquals(24_000L, serial)
        assertEquals(8_000L, bounded)
        assertTrue(workers in 1..8)
    }

    @Test
    fun speedEstimatorConvergesOnNoisySamplesWithoutAbsurdLabels() {
        val estimator = TransferSpeedEstimator()
        val trueRate = 2.0 * 1024 * 1024 // 2 MiB/s
        var bytes = 0L
        var last = TransferSpeedSnapshot()
        for (tick in 0..200) {
            // +-15% jitter around the true rate, deterministic sawtooth.
            val jitter = 1.0 + 0.15 * if (tick % 2 == 0) 1.0 else -1.0
            bytes += (trueRate * 0.1 * jitter).toLong()
            last = estimator.sample(bytes, tick * 100L)
        }
        val reported = last.bytesPerSecond ?: error("speed should be known after 20 s")
        val error = kotlin.math.abs(reported - trueRate.toLong()).toDouble() / trueRate
        println("estimator: reported=${reported}B/s true=${trueRate}B/s error=${"%.1f".format(error * 100)}%")
        assertTrue("smoothed error should stay under 25%", error < 0.25)
        assertTrue(last.etaSeconds == null || last.etaSeconds!! > 0)
    }
}
