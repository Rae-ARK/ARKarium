package com.arkarium.app.data

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Rung 3 of docs/implementation/catalogue-browse-redesign.md - C1 (slug is untrusted
// before it's a folder name) and the replace-not-accumulate behavior of write().
class CatalogueStorageTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun storage() = CatalogueStorage(File(tmp.root, "catalogue"))
    private fun dir(slug: String) = File(tmp.root, "catalogue/$slug")

    @Test
    fun writesCoverAndMetadataAndReturnsFileUri() {
        val uri = storage().write("sbm", byteArrayOf(1, 2, 3), "cover.jpg", "{}".toByteArray())

        assertNotNull(uri)
        assertTrue(uri!!.startsWith("file:///"))
        assertTrue(uri.endsWith("/catalogue/sbm/cover.jpg"))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir("sbm"), "cover.jpg").readBytes())
        assertEquals("{}", File(dir("sbm"), "metadata.json").readText())
    }

    @Test
    fun slugWithSpacesIsAllowedAsAFolderName() {
        storage().write("Enigmatic Pathways", null, null, "{}".toByteArray())
        assertTrue(File(dir("Enigmatic Pathways"), "metadata.json").exists())
    }

    @Test
    fun noCoverReturnsNullAndLeavesNoTempFiles() {
        val uri = storage().write("sbm", null, null, "{}".toByteArray())
        assertNull(uri)
        assertEquals(listOf("metadata.json"), dir("sbm").list()!!.toList())
    }

    @Test
    fun refreshReplacesCoverWhenExtensionChanges() {
        val s = storage()
        s.write("sbm", byteArrayOf(1), "cover.jpg", null)
        val uri = s.write("sbm", byteArrayOf(2), "COVER.PNG", null)

        assertTrue(uri!!.endsWith("/cover.png"))
        assertFalse(File(dir("sbm"), "cover.jpg").exists())
        assertArrayEquals(byteArrayOf(2), File(dir("sbm"), "cover.png").readBytes())
    }

    @Test
    fun cachedFilesAreRemovedWhenRelayNoLongerHasThem() {
        val s = storage()
        s.write("sbm", byteArrayOf(1), "cover.jpg", "{}".toByteArray())
        assertNull(s.write("sbm", null, null, null))

        assertEquals(0, dir("sbm").list()!!.size)
    }

    @Test
    fun unsafeSlugsAreRejectedAndWriteNothing() {
        val s = storage()
        for (bad in listOf("..", ".", "", "/abs", "a/b", "a/../b", "..\\x")) {
            try {
                s.write(bad, null, null, "{}".toByteArray())
                throw AssertionError("expected UnsafePathException for '$bad'")
            } catch (e: UnsafePathException) {
            }
        }
        // Rejected before any directory is created.
        assertFalse(File(tmp.root, "catalogue").exists())
    }

    @Test
    fun nonImageCoverExtensionIsRejected() {
        try {
            storage().write("sbm", byteArrayOf(1), "cover.sh", null)
            throw AssertionError("expected UnsafePathException")
        } catch (e: UnsafePathException) {
        }
    }

    @Test
    fun deleteRemovesTheSlugFolderAndIsIdempotent() {
        val s = storage()
        s.write("sbm", byteArrayOf(1), "cover.jpg", "{}".toByteArray())
        s.delete("sbm")
        s.delete("sbm")
        assertFalse(dir("sbm").exists())
    }
}
