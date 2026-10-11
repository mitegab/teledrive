package com.teledrive.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalFileStorageTest {
    @Test
    fun importsAtomicallyIntoManagedStorageAndRemovesOnlyThatCopy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = LocalFileStorage(context)
        val source = File.createTempFile("tdlib-result-", ".bin", context.cacheDir).apply { writeText("offline bytes") }

        val managed = storage.importCompleted(source.absolutePath, "42:100", "../report.pdf", source.length())

        assertTrue(managed.startsWith(context.filesDir.resolve("downloads").canonicalPath + File.separator))
        assertTrue(storage.isAvailable(managed, source.length()))
        assertTrue(storage.importCompleted(source.absolutePath, "42:100", "../report.pdf", source.length()) == managed)
        storage.remove(managed)
        assertFalse(storage.isAvailable(managed, source.length()))
        assertTrue(source.isFile)
        source.delete()
    }
}
