package com.teledrive

import android.app.Application
import com.teledrive.transfers.TransferWorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TeleDriveApplication : Application() {
    @Inject
    lateinit var transferWorkScheduler: TransferWorkScheduler

    override fun onCreate() {
        super.onCreate()
        // Room transfer rows survive process death; this single unique worker rechecks them on every app start.
        transferWorkScheduler.enqueue()
    }
}
