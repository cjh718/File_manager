package com.folio.files

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test

class FileOperationsTest {
    private lateinit var temporaryDirectory: File
    private lateinit var primary: File
    private lateinit var destination: File
    private lateinit var operations: FileOperations

    @Before
    fun setUp() {
        temporaryDirectory = Files.createTempDirectory("myfiles-operations-test").toFile()
        primary = File(temporaryDirectory, "primary").apply { assertTrue(mkdir()) }
        destination = File(primary, "Destination").apply { assertTrue(mkdir()) }
        operations = FileOperations(primary)
    }

    @After
    fun tearDown() {
        temporaryDirectory.deleteRecursively()
    }

    @Test
    fun copiesNestedFoldersAndReadOnlyFilesWithoutDeletingSources() {
        val folder = File(primary, "Folder").apply { assertTrue(mkdir()) }
        File(folder, "nested").apply { assertTrue(mkdir()) }
        File(folder, "nested/note.txt").writeText("nested contents")
        val readOnly = File(primary, "read-only.txt").apply {
            writeText("read only")
            setWritable(false, false)
        }

        val result = operations.copy(listOf(folder, readOnly), destination)

        assertEquals(2, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(FileOperations.Status.COPIED, result.items[0].status)
        assertEquals(
            "nested contents",
            File(destination, "Folder/nested/note.txt").readText(),
        )
        assertEquals("read only", File(destination, "read-only.txt").readText())
        assertTrue(folder.exists())
        assertTrue(readOnly.exists())
    }

    @Test
    fun copyMayReadOutsidePrimaryButMoveMayNot() {
        val outside = File(temporaryDirectory, "outside.txt").apply { writeText("shared") }

        val copy = operations.copy(outside, destination)
        val moveValidation = operations.validateMove(listOf(outside), destination)

        assertEquals(FileOperations.Status.COPIED, copy.status)
        assertEquals("shared", File(destination, "outside.txt").readText())
        assertEquals(
            FileOperations.Error.SOURCE_OUTSIDE_PRIMARY,
            moveValidation.items.single().error,
        )
        assertTrue(outside.exists())
    }

    @Test
    fun movesIntoAnotherPrimaryDirectory() {
        val source = File(primary, "move.txt").apply { writeText("contents") }

        val result = operations.move(source, destination)

        assertEquals(FileOperations.Status.MOVED, result.status)
        assertFalse(source.exists())
        assertEquals("contents", File(destination, "move.txt").readText())
    }

    @Test
    fun rejectsWholeBatchWhenAnySourceIsMissing() {
        val valid = File(primary, "valid.txt").apply { writeText("safe") }
        val missing = File(primary, "missing.txt")

        val result = operations.copy(listOf(valid, missing), destination)

        assertEquals(FileOperations.Error.BATCH_REJECTED, result.items[0].error)
        assertEquals(FileOperations.Error.MISSING_SOURCE, result.items[1].error)
        assertFalse(File(destination, "valid.txt").exists())
        assertTrue(valid.exists())
    }

    @Test
    fun rejectsDuplicateAndOverlappingSources() {
        val folder = File(primary, "parent").apply { assertTrue(mkdir()) }
        val child = File(folder, "child.txt").apply { writeText("child") }

        val duplicate = operations.validateCopy(listOf(child, child), destination)
        val overlap = operations.validateCopy(listOf(folder, child), destination)

        assertTrue(duplicate.items.all { it.error == FileOperations.Error.DUPLICATE_SOURCE })
        assertTrue(overlap.items.all { it.error == FileOperations.Error.OVERLAPPING_SOURCES })
        assertTrue(child.exists())
    }

    @Test
    fun rejectsExistingDestinationsAndDuplicateSourceNames() {
        val source = File(primary, "collision.txt").apply { writeText("new") }
        File(destination, "collision.txt").writeText("old")

        val collision = operations.copy(source, destination)

        val firstDirectory = File(primary, "first").apply { assertTrue(mkdir()) }
        val secondDirectory = File(primary, "second").apply { assertTrue(mkdir()) }
        val first = File(firstDirectory, "same.txt").apply { writeText("first") }
        val second = File(secondDirectory, "same.txt").apply { writeText("second") }
        val duplicateTargets = operations.validateCopy(listOf(first, second), destination)

        assertEquals(FileOperations.Error.DESTINATION_EXISTS, collision.error)
        assertEquals("old", File(destination, "collision.txt").readText())
        assertTrue(duplicateTargets.items.all {
            it.error == FileOperations.Error.DESTINATION_CONFLICT
        })
    }

    @Test
    fun rejectsSameLocationMoveAndRecursiveFolderDestinations() {
        val source = File(primary, "same.txt").apply { writeText("contents") }
        val folder = File(primary, "folder").apply { assertTrue(mkdir()) }
        val descendant = File(folder, "child").apply { assertTrue(mkdir()) }

        val sameLocation = operations.validateMove(listOf(source), primary)
        val self = operations.validateCopy(listOf(folder), folder)
        val recursive = operations.validateMove(listOf(folder), descendant)

        assertEquals(FileOperations.Error.SAME_LOCATION, sameLocation.items.single().error)
        assertEquals(FileOperations.Error.RECURSIVE_OPERATION, self.items.single().error)
        assertEquals(FileOperations.Error.RECURSIVE_OPERATION, recursive.items.single().error)
        assertTrue(source.exists())
        assertTrue(folder.exists())
    }

    @Test
    fun rejectsSymbolicLinkSourceAndDoesNotCopyNestedLink() {
        val target = File(temporaryDirectory, "target.txt").apply { writeText("target") }
        val link = File(primary, "link.txt")
        val folder = File(primary, "linked-folder").apply { assertTrue(mkdir()) }
        val nestedLink = File(folder, "nested-link.txt")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            Files.createSymbolicLink(nestedLink.toPath(), target.toPath())
        } catch (e: Exception) {
            assumeNoException(e)
        }

        val linkResult = operations.copy(link, destination)
        val nestedResult = operations.copy(folder, destination)

        assertEquals(FileOperations.Error.SYMBOLIC_LINK, linkResult.error)
        assertEquals(FileOperations.Status.FAILED, nestedResult.status)
        assertEquals(FileOperations.Error.COPY_FAILED, nestedResult.error)
        assertFalse(File(destination, "link.txt").exists())
        assertFalse(File(destination, "linked-folder").exists())
        assertTrue(Files.isSymbolicLink(nestedLink.toPath()))
        assertEquals("target", target.readText())
        assertNull(destination.listFiles()?.firstOrNull { it.name.startsWith(".myfiles-") })
    }

    @Test
    fun rejectsSymbolicLinkDestinationWithoutWritingTarget() {
        val source = File(primary, "escape.txt").apply { writeText("safe") }
        val outsideTarget = File(temporaryDirectory, "outside-target.txt")
        val destinationLink = File(destination, source.name)
        try {
            Files.createSymbolicLink(destinationLink.toPath(), outsideTarget.toPath())
        } catch (e: Exception) {
            assumeNoException(e)
        }

        val result = operations.copy(source, destination)

        assertEquals(FileOperations.Error.DESTINATION_EXISTS, result.error)
        assertFalse(outsideTarget.exists())
        assertTrue(Files.isSymbolicLink(destinationLink.toPath()))
        assertEquals("safe", source.readText())
    }

    @Test
    fun failedItemDoesNotPreventLaterIndependentCopy() {
        val target = File(temporaryDirectory, "target.txt").apply { writeText("target") }
        val badFolder = File(primary, "bad").apply { assertTrue(mkdir()) }
        try {
            Files.createSymbolicLink(File(badFolder, "link").toPath(), target.toPath())
        } catch (e: Exception) {
            assumeNoException(e)
        }
        val good = File(primary, "good.txt").apply { writeText("good") }

        val result = operations.copy(listOf(badFolder, good), destination)

        assertEquals(1, result.successCount)
        assertEquals(1, result.failureCount)
        assertEquals(FileOperations.Status.FAILED, result.items[0].status)
        assertEquals(FileOperations.Status.COPIED, result.items[1].status)
        assertTrue(badFolder.exists())
        assertEquals("good", File(destination, "good.txt").readText())
    }

    @Test
    fun deleteIsSequentialAndRejectsOutsideOrOverlappingInputs() {
        val first = File(primary, "first").apply { assertTrue(mkdir()) }
        File(first, "child.txt").writeText("child")
        val second = File(primary, "second.txt").apply { writeText("second") }
        val outside = File(temporaryDirectory, "outside.txt").apply { writeText("outside") }

        val deleted = operations.delete(listOf(first, second))
        val rejected = operations.delete(outside)

        assertEquals(2, deleted.successCount)
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertEquals(FileOperations.Error.SOURCE_OUTSIDE_PRIMARY, rejected.error)
        assertTrue(outside.exists())
    }

    @Test
    fun emptyBatchAndSuccessfulValidationHaveExplicitResults() {
        val empty = operations.copy(emptyList(), destination)
        val source = File(primary, "valid.txt").apply { writeText("valid") }
        val validated = operations.validateCopy(listOf(source), destination)

        assertEquals(FileOperations.Error.EMPTY_BATCH, empty.error)
        assertFalse(empty.isSuccess)
        assertTrue(validated.isSuccess)
        assertEquals(FileOperations.Status.VALIDATED, validated.items.single().status)
        assertFalse(File(destination, "valid.txt").exists())
    }
}
