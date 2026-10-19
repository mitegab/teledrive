package com.teledrive

import android.app.Application
import com.teledrive.core.model.TeleDriveLog
import com.teledrive.transfers.TransferWorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TeleDriveApplication : Application() {
    @Inject
    lateinit var transferWorkScheduler: TransferWorkScheduler

    override fun onCreate() {
        super.onCreate()
        // Structured debug events (redacted) under the TeleDrive logcat tag.
        TeleDriveLog.sink = { line -> android.util.Log.d(TeleDriveLog.TAG, line) }
        // Room transfer rows survive process death; this single unique worker rechecks them on every app start.
        transferWorkScheduler.enqueue()
    }
}
