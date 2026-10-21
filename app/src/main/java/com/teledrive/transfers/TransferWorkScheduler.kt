package com.teledrive.transfers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the single transfer drain.
 *
 * Immediate drains use KEEP, so a running drain is never cancelled by a new app
 * start. Durable retries use a separate unique name with REPLACE, so only the
 * earliest known retry time is pending at any moment.
 */
@Singleton
class TransferWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(delaySeconds: Long = 0L) {
        if (delaySeconds > 0L) enqueueRetry(delaySeconds) else enqueueImmediate()
    }

    private fun enqueueImmediate() {
        val request = OneTimeWorkRequestBuilder<TransferDrainWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private fun enqueueRetry(delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<TransferDrainWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(delaySeconds.coerceIn(1L, 15L * 60L), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val IMMEDIATE_WORK = "teledrive-transfer-drain"
        const val RETRY_WORK = "teledrive-transfer-retry"
    }
}
