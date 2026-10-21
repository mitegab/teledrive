package com.teledrive

import android.app.Application
import com.teledrive.core.model.TeleDriveLog
import com.teledrive.files.StartupReconciler
import com.teledrive.transfers.TransferWorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class TeleDriveApplication : Application() {
    @Inject
    lateinit var transferWorkScheduler: TransferWorkScheduler

    @Inject
    lateinit var startupReconciler: StartupReconciler

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Structured debug events (redacted) under the TeleDrive logcat tag.
        TeleDriveLog.sink = { line -> android.util.Log.d(TeleDriveLog.TAG, line) }
        // Settle transfers/local flags left by a previous process before the UI reads them.
        applicationScope.launch {
            runCatching { startupReconciler.reconcile() }
                .onFailure { error -> TeleDriveLog.d("startup.reconcile_failed", "error" to error.message) }
            // Room transfer rows survive process death; recheck them once per app start.
            transferWorkScheduler.enqueue()
        }
    }
}
