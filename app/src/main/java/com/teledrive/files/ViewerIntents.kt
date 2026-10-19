package com.teledrive.files

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** Intent constructors shared by the external viewer and covered by focused tests. */
object ViewerIntents {
    fun view(uri: Uri, mimeType: String, displayName: String): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(displayName, uri)
        }

    fun share(uri: Uri, mimeType: String, displayName: String): Intent =
        Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(displayName, uri)
        }
}
