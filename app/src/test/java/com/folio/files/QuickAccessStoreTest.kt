package com.folio.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAccessStoreTest {

    @Test
    fun defaultsMatchExistingQuickAccessFoldersAndOrder() {
        val defaults = QuickAccessStore.defaults()

        assertEquals(
            listOf("downloads", "dcim", "movies", "music", "documents", "recordings"),
            defaults.map { it.id },
        )
        assertEquals(
            listOf("Download", "DCIM", "Movies", "Music", "Documents", "Recordings"),
            defaults.map { it.relativePath },
        )
        assertTrue(defaults.all { it.isDefault })
        assertTrue(defaults.all { it.titleRes != 0 && it.subtitleRes != 0 && it.iconRes != 0 })
    }

    @Test
    fun relativePathsRejectEscapeAbsoluteAndMalformedValues() {
        val valid = listOf("Download", "Projects/2026", ".hidden/folder")
        val invalid = listOf(
            "",
            " ",
            "/Download",
            "../Download",
            "Download/../DCIM",
            "Download/./Chrome",
            "Download//Chrome",
            "Download/",
            "bad\u0000name",
        )

        assertTrue(valid.all(QuickAccessPaths::isValid))
        assertTrue(invalid.none(QuickAccessPaths::isValid))
    }

    @Test
    fun idsAreUniqueFriendlyAndBounded() {
        assertTrue(QuickAccessIds.isValid("downloads"))
        assertTrue(QuickAccessIds.isValid("custom-1234_ABCD.xyz"))
        assertFalse(QuickAccessIds.isValid(""))
        assertFalse(QuickAccessIds.isValid("-leading"))
        assertFalse(QuickAccessIds.isValid("contains/slash"))
        assertFalse(QuickAccessIds.isValid("a".repeat(65)))
    }

    @Test
    fun orderingMovesExactlyOnePositionAndPreservesAllEntries() {
        val entries = QuickAccessStore.defaults()

        val movedUp = QuickAccessOrdering.move(entries, "movies", -1)
        val movedDown = QuickAccessOrdering.move(entries, "downloads", 1)

        assertEquals(
            listOf("downloads", "movies", "dcim", "music", "documents", "recordings"),
            movedUp?.map { it.id },
        )
        assertEquals(
            listOf("dcim", "downloads", "movies", "music", "documents", "recordings"),
            movedDown?.map { it.id },
        )
        assertEquals(6, movedUp?.distinctBy { it.id }?.size)
        assertEquals(6, movedDown?.distinctBy { it.id }?.size)
    }

    @Test
    fun orderingRejectsUnknownIdsAndBoundaryNoOps() {
        val entries = QuickAccessStore.defaults()

        assertNull(QuickAccessOrdering.move(entries, "missing", 1))
        assertNull(QuickAccessOrdering.move(entries, "downloads", -1))
        assertNull(QuickAccessOrdering.move(entries, "recordings", 1))
        assertNull(QuickAccessOrdering.move(emptyList(), "downloads", 1))
    }
}
