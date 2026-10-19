package com.teledrive.core.model

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UploadStagingRulesTest {

    private val root: File = createTempDirectory()

    @Test
    fun stagedTargetKeepsTheRealFileNameAndExtension() {
        val target = UploadStagingRules.createStagedTarget(root, "report.pdf")

        assertEquals("report.pdf", target.name)
        assertTrue("expected <root>/<uuid>/report.pdf, was $target", target.parentFile.parentFile == root.canonicalFile)
        assertTrue(target.path.endsWith(File.separator + "report.pdf"))
    }

    @Test
    fun stagedTargetStaysInsideTheStagingDirectoryForTraversalNames() {
        listOf("../../evil.pdf", "..\\..\\evil.pdf", "/etc/passwd", "a/b/c.pdf").forEach { hostile ->
            val target = UploadStagingRules.createStagedTarget(root, hostile)
            val canonicalRoot = root.canonicalFile
            assertTrue("escaped root: $target", target.path.startsWith(canonicalRoot.path + File.separator))
            assertEquals(target.name, target.canonicalFile.name)
            assertFalse(target.name.contains('/'))
            assertFalse(target.name.contains('\\'))
        }
    }

    @Test
    fun rejectsEntryIdsThatCouldEscapeTheStagingDirectory() {
        try {
            UploadStagingRules.createStagedTarget(root, "report.pdf", entryId = "../escape")
            fail("expected IOException")
        } catch (expected: IOException) {
            // expected
        }
    }

    @Test
    fun longUnicodeNamesStillFitTheFileSystem() {
        val longName = "\u1230".repeat(300) + ".pdf"
        val target = UploadStagingRules.createStagedTarget(root, longName)

        assertTrue(target.name.endsWith(".pdf"))
        assertTrue("name too long: ${target.name.toByteArray().size}", target.name.toByteArray(Charsets.UTF_8).size <= 255)
    }

    @Test
    fun namedEntrySourcesAreRecognizedAndRemovedWithTheirEntryDirectory() {
        val target = UploadStagingRules.createStagedTarget(root, "report.pdf")
        target.writeText("bytes")

        assertTrue(UploadStagingRules.isStagedSource(root, target.absolutePath))
        UploadStagingRules.removeStagedSource(root, target.absolutePath)

        assertFalse(target.exists())
        assertFalse("entry directory should be removed when empty", target.parentFile.exists())
    }

    @Test
    fun legacyFlatPartFilesRemainValidSourcesWithoutMigration() {
        val legacy = root.resolve("upload-123.part").apply { writeText("staged by an older release") }

        assertTrue(UploadStagingRules.isStagedSource(root, legacy.absolutePath))
        UploadStagingRules.removeStagedSource(root, legacy.absolutePath)

        assertFalse(legacy.exists())
        assertTrue("the staging root itself must survive", root.isDirectory)
    }

    @Test
    fun pathsOutsideTheStagingDirectoryAreNeverSources() {
        val outsider = File.createTempFile("outsider-", ".part").apply { writeText("not staged") }
        val nestedEscape = root.resolve("a").resolve("b").resolve("c.pdf").apply {
            parentFile.mkdirs()
            writeText("too deep")
        }

        assertFalse(UploadStagingRules.isStagedSource(root, outsider.absolutePath))
        assertFalse(UploadStagingRules.isStagedSource(root, nestedEscape.absolutePath))
        assertFalse(UploadStagingRules.isStagedSource(root, null))
        assertFalse(UploadStagingRules.isStagedSource(root, root.resolve("missing.pdf").absolutePath))

        outsider.delete()
    }

    @Test
    fun cleanupRemovesStaleEntriesButKeepsRecentOnes() {
        val stale = UploadStagingRules.createStagedTarget(root, "old.pdf").apply { writeText("old") }
        val fresh = UploadStagingRules.createStagedTarget(root, "new.pdf").apply { writeText("new") }
        val cutoff = System.currentTimeMillis() - 1_000
        stale.setLastModified(cutoff - 60_000)

        UploadStagingRules.cleanup(root, cutoff)

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    private fun createTempDirectory(): File =
        File.createTempFile("teledrive-staging-", ".dir").let { placeholder ->
            placeholder.delete()
            File(placeholder.parentFile, placeholder.name.removeSuffix(".dir")).apply { mkdirs() }
        }
}
