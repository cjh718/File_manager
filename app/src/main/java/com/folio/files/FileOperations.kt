package com.folio.files

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/**
 * Sequential local-file operations. These methods block, so callers should invoke them on an IO
 * dispatcher. No method overwrites an existing destination or silently chooses another name.
 */
class FileOperations(private val primaryStorage: File) {

    enum class Operation { COPY, MOVE, DELETE }

    enum class Status {
        VALIDATED,
        COPIED,
        MOVED,
        DELETED,
        COPIED_BUT_SOURCE_DELETE_FAILED,
        FAILED,
    }

    enum class Error {
        EMPTY_BATCH,
        INVALID_PRIMARY_STORAGE,
        INVALID_DESTINATION,
        DESTINATION_OUTSIDE_PRIMARY,
        SOURCE_OUTSIDE_PRIMARY,
        MISSING_SOURCE,
        UNREADABLE_SOURCE,
        INVALID_SOURCE,
        SYMBOLIC_LINK,
        DUPLICATE_SOURCE,
        OVERLAPPING_SOURCES,
        SAME_LOCATION,
        RECURSIVE_OPERATION,
        DESTINATION_EXISTS,
        DESTINATION_CONFLICT,
        BATCH_REJECTED,
        COPY_FAILED,
        MOVE_FAILED,
        DELETE_FAILED,
        SOURCE_DELETE_FAILED,
    }

    data class ItemResult(
        val operation: Operation,
        val source: File,
        val destination: File?,
        val status: Status,
        val error: Error? = null,
        val message: String? = null,
    ) {
        val isSuccess: Boolean
            get() = status == Status.VALIDATED || status == Status.COPIED ||
                status == Status.MOVED || status == Status.DELETED

        val isPartial: Boolean
            get() = status == Status.COPIED_BUT_SOURCE_DELETE_FAILED
    }

    data class BatchResult(
        val items: List<ItemResult>,
        val error: Error? = null,
    ) {
        val successCount: Int get() = items.count { it.isSuccess }
        val failureCount: Int get() = items.count { !it.isSuccess }
        val isSuccess: Boolean get() = error == null && items.isNotEmpty() && failureCount == 0
    }

    private data class Plan(
        val operation: Operation,
        val source: File,
        val destinationDirectory: File?,
        val destination: File?,
    )

    private data class Preparation(
        val plans: List<Plan> = emptyList(),
        val failure: BatchResult? = null,
    )

    private data class ExecutionFailure(val error: Error, val message: String)

    fun copy(source: File, destinationDirectory: File): ItemResult =
        copy(listOf(source), destinationDirectory).items.single()

    fun copy(sources: List<File>, destinationDirectory: File): BatchResult =
        execute(Operation.COPY, sources, destinationDirectory)

    fun move(source: File, destinationDirectory: File): ItemResult =
        move(listOf(source), destinationDirectory).items.single()

    fun move(sources: List<File>, destinationDirectory: File): BatchResult =
        execute(Operation.MOVE, sources, destinationDirectory)

    fun delete(source: File): ItemResult = delete(listOf(source)).items.single()

    fun delete(sources: List<File>): BatchResult = execute(Operation.DELETE, sources, null)

    /** Returns validation failures without changing the filesystem. */
    fun validateCopy(sources: List<File>, destinationDirectory: File): BatchResult =
        prepare(Operation.COPY, sources, destinationDirectory).asResult()

    /** Returns validation failures without changing the filesystem. */
    fun validateMove(sources: List<File>, destinationDirectory: File): BatchResult =
        prepare(Operation.MOVE, sources, destinationDirectory).asResult()

    /** Returns validation failures without changing the filesystem. */
    fun validateDelete(sources: List<File>): BatchResult =
        prepare(Operation.DELETE, sources, null).asResult()

    private fun execute(
        operation: Operation,
        sources: List<File>,
        destinationDirectory: File?,
    ): BatchResult {
        val preparation = prepare(operation, sources, destinationDirectory)
        preparation.failure?.let { return it }

        val results = preparation.plans.map { plan ->
            // Recheck each item immediately before touching it. Earlier items may have changed the
            // filesystem, and other apps may change it at any time.
            val current = prepare(operation, listOf(plan.source), plan.destinationDirectory)
            current.failure?.items?.singleOrNull() ?: when (operation) {
                Operation.COPY -> copyItem(plan)
                Operation.MOVE -> moveItem(plan)
                Operation.DELETE -> deleteItem(plan)
            }
        }
        return BatchResult(results)
    }

    private fun prepare(
        operation: Operation,
        sources: List<File>,
        destinationDirectory: File?,
    ): Preparation {
        if (sources.isEmpty()) return Preparation(failure = BatchResult(emptyList(), Error.EMPTY_BATCH))

        val primary = canonical(primaryStorage)
        if (primary == null || hasSymbolicLinkComponent(primaryStorage) || !primary.isDirectory) {
            return invalidBatch(operation, sources, destinationDirectory, Error.INVALID_PRIMARY_STORAGE)
        }

        val destination = if (operation == Operation.DELETE) null else canonical(destinationDirectory)
        if (operation != Operation.DELETE) {
            if (destination == null || destinationDirectory == null ||
                hasSymbolicLinkComponent(destinationDirectory) ||
                !destination.isDirectory || !destination.canWrite()
            ) {
                return invalidBatch(operation, sources, destinationDirectory, Error.INVALID_DESTINATION)
            }
            if (!isWithin(primary, destination)) {
                return invalidBatch(
                    operation,
                    sources,
                    destinationDirectory,
                    Error.DESTINATION_OUTSIDE_PRIMARY,
                )
            }
        }

        val plans = arrayListOf<Plan?>()
        val errors = arrayListOf<Error?>()
        sources.forEach { original ->
            val source = canonical(original)
            var error: Error? = null
            if (source == null || hasSymbolicLinkComponent(original)) {
                error = if (source != null && Files.isSymbolicLink(original.toPath())) {
                    Error.SYMBOLIC_LINK
                } else if (source == null) Error.MISSING_SOURCE else Error.SYMBOLIC_LINK
            } else if (!source.exists()) {
                error = Error.MISSING_SOURCE
            } else if (!source.isFile && !source.isDirectory) {
                error = Error.INVALID_SOURCE
            } else if (!source.canRead()) {
                error = Error.UNREADABLE_SOURCE
            } else if (source.name.isBlank() || source == primary) {
                error = Error.INVALID_SOURCE
            } else if (operation != Operation.COPY && !isWithin(primary, source)) {
                error = Error.SOURCE_OUTSIDE_PRIMARY
            }

            val directTarget = if (source != null && destination != null && source.name.isNotBlank()) {
                File(destination, source.name)
            } else {
                null
            }
            val target = directTarget?.let(::canonical)
            if (error == null && operation != Operation.DELETE && source != null) {
                when {
                    directTarget != null && Files.isSymbolicLink(directTarget.toPath()) ->
                        error = Error.DESTINATION_EXISTS
                    target == null -> error = Error.INVALID_DESTINATION
                    !isWithin(destination!!, target) || !isWithin(primary, target) ->
                        error = Error.DESTINATION_OUTSIDE_PRIMARY
                    operation == Operation.MOVE && source.parentFile == destination ->
                        error = Error.SAME_LOCATION
                    existsWithoutFollowingLinks(directTarget!!) -> error = Error.DESTINATION_EXISTS
                    source.isDirectory && isWithin(source, destination) ->
                        error = Error.RECURSIVE_OPERATION
                }
            }

            plans += if (source == null) null else Plan(operation, source, destination, target)
            errors += error
        }

        for (first in plans.indices) {
            val firstSource = plans[first]?.source ?: continue
            for (second in first + 1 until plans.size) {
                val secondSource = plans[second]?.source ?: continue
                when {
                    firstSource == secondSource -> {
                        errors[first] = errors[first] ?: Error.DUPLICATE_SOURCE
                        errors[second] = errors[second] ?: Error.DUPLICATE_SOURCE
                    }
                    isWithin(firstSource, secondSource) || isWithin(secondSource, firstSource) -> {
                        errors[first] = errors[first] ?: Error.OVERLAPPING_SOURCES
                        errors[second] = errors[second] ?: Error.OVERLAPPING_SOURCES
                    }
                }
            }
        }

        if (operation != Operation.DELETE) {
            for (first in plans.indices) {
                val firstTarget = plans[first]?.destination ?: continue
                for (second in first + 1 until plans.size) {
                    if (firstTarget == plans[second]?.destination) {
                        errors[first] = errors[first] ?: Error.DESTINATION_CONFLICT
                        errors[second] = errors[second] ?: Error.DESTINATION_CONFLICT
                    }
                }
            }
        }

        if (errors.any { it != null }) {
            val items = sources.mapIndexed { index, original ->
                val plan = plans[index]
                failed(
                    operation,
                    plan?.source ?: original.absoluteFile,
                    plan?.destination,
                    errors[index] ?: Error.BATCH_REJECTED,
                )
            }
            return Preparation(failure = BatchResult(items))
        }

        return Preparation(plans = plans.filterNotNull())
    }

    private fun copyItem(plan: Plan): ItemResult {
        val failure = copyThroughStaging(plan.source, plan.destinationDirectory!!, plan.destination!!)
        return if (failure == null) {
            ItemResult(Operation.COPY, plan.source, plan.destination, Status.COPIED)
        } else {
            failed(Operation.COPY, plan.source, plan.destination, failure.error, failure.message)
        }
    }

    private fun moveItem(plan: Plan): ItemResult {
        val source = plan.source
        val destination = plan.destination!!
        try {
            Files.move(source.toPath(), destination.toPath())
            return ItemResult(Operation.MOVE, source, destination, Status.MOVED)
        } catch (renameFailure: Exception) {
            if (existsWithoutFollowingLinks(destination)) {
                return failed(
                    Operation.MOVE,
                    source,
                    destination,
                    Error.DESTINATION_EXISTS,
                    renameFailure.message,
                )
            }
        }

        val failure = copyThroughStaging(source, plan.destinationDirectory!!, destination)
        if (failure != null) {
            val error = if (failure.error == Error.DESTINATION_EXISTS) {
                Error.DESTINATION_EXISTS
            } else {
                Error.MOVE_FAILED
            }
            return failed(Operation.MOVE, source, destination, error, failure.message)
        }

        return if (deleteTreeWithoutFollowingLinks(source)) {
            ItemResult(Operation.MOVE, source, destination, Status.MOVED)
        } else {
            ItemResult(
                operation = Operation.MOVE,
                source = source,
                destination = destination,
                status = Status.COPIED_BUT_SOURCE_DELETE_FAILED,
                error = Error.SOURCE_DELETE_FAILED,
                message = "The destination is complete, but the source could not be fully deleted.",
            )
        }
    }

    private fun deleteItem(plan: Plan): ItemResult {
        return if (deleteTreeWithoutFollowingLinks(plan.source)) {
            ItemResult(Operation.DELETE, plan.source, null, Status.DELETED)
        } else {
            failed(Operation.DELETE, plan.source, null, Error.DELETE_FAILED)
        }
    }

    private fun copyThroughStaging(
        source: File,
        destinationDirectory: File,
        destination: File,
    ): ExecutionFailure? {
        val staging = try {
            createStaging(source, destinationDirectory)
        } catch (e: Exception) {
            return ExecutionFailure(Error.COPY_FAILED, e.displayMessage())
        }

        try {
            populateStaging(source, staging)
            Files.move(staging.toPath(), destination.toPath())
            return null
        } catch (e: Exception) {
            val error = if (existsWithoutFollowingLinks(destination)) {
                Error.DESTINATION_EXISTS
            } else {
                Error.COPY_FAILED
            }
            return ExecutionFailure(error, e.displayMessage())
        } finally {
            if (existsWithoutFollowingLinks(staging)) {
                deleteTreeWithoutFollowingLinks(staging)
            }
        }
    }

    private fun createStaging(source: File, directory: File): File {
        repeat(10) {
            val candidate = File(directory, ".myfiles-${UUID.randomUUID()}.tmp")
            try {
                if (source.isDirectory) {
                    Files.createDirectory(candidate.toPath())
                } else {
                    Files.createFile(candidate.toPath())
                }
                return candidate
            } catch (_: FileAlreadyExistsException) {
                // A random staging name already exists; reserve another without replacing it.
            }
        }
        throw IOException("Could not reserve a temporary destination.")
    }

    private fun populateStaging(source: File, staging: File) {
        if (isDirectoryWithoutFollowingLinks(source)) {
            val children = source.listFiles() ?: throw IOException("Cannot read ${source.path}.")
            children.sortedBy { it.name }.forEach { child ->
                copyNode(child, File(staging, child.name))
            }
            staging.setLastModified(source.lastModified())
        } else if (isRegularFileWithoutFollowingLinks(source)) {
            copyFileContents(source, staging)
        } else {
            throw IOException("The source is missing, unsupported, or a symbolic link: ${source.path}")
        }
    }

    private fun copyNode(source: File, destination: File) {
        if (Files.isSymbolicLink(source.toPath())) {
            throw IOException("Symbolic links are not copied: ${source.path}")
        }
        when {
            isDirectoryWithoutFollowingLinks(source) -> {
                Files.createDirectory(destination.toPath())
                populateStaging(source, destination)
            }
            isRegularFileWithoutFollowingLinks(source) -> {
                Files.createFile(destination.toPath())
                copyFileContents(source, destination)
            }
            else -> throw IOException("The source is missing or unsupported: ${source.path}")
        }
    }

    private fun copyFileContents(source: File, destination: File) {
        if (!isRegularFileWithoutFollowingLinks(source)) {
            throw IOException("The source is not a regular file: ${source.path}")
        }
        FileInputStream(source).buffered().use { input ->
            FileOutputStream(destination, false).buffered().use { output ->
                input.copyTo(output)
            }
        }
        destination.setLastModified(source.lastModified())
    }

    private fun deleteTreeWithoutFollowingLinks(file: File): Boolean {
        if (Files.isSymbolicLink(file.toPath()) || !existsWithoutFollowingLinks(file)) return false
        if (isDirectoryWithoutFollowingLinks(file)) {
            val children = file.listFiles() ?: return false
            var complete = true
            children.forEach { child ->
                if (!deleteTreeWithoutFollowingLinks(child)) complete = false
            }
            if (!complete) return false
        }
        return try {
            Files.deleteIfExists(file.toPath())
        } catch (_: Exception) {
            false
        }
    }

    private fun invalidBatch(
        operation: Operation,
        sources: List<File>,
        destinationDirectory: File?,
        error: Error,
    ): Preparation {
        val items = sources.map { source ->
            failed(operation, source.absoluteFile, destinationDirectory?.absoluteFile, error)
        }
        return Preparation(failure = BatchResult(items))
    }

    private fun Preparation.asResult(): BatchResult =
        failure ?: BatchResult(plans.map { plan ->
            ItemResult(plan.operation, plan.source, plan.destination, Status.VALIDATED)
        })

    private fun failed(
        operation: Operation,
        source: File,
        destination: File?,
        error: Error,
        message: String? = null,
    ) = ItemResult(operation, source, destination, Status.FAILED, error, message)

    private fun canonical(file: File?): File? = try {
        file?.canonicalFile
    } catch (_: Exception) {
        null
    }

    private fun isWithin(parent: File, child: File): Boolean = child.toPath().startsWith(parent.toPath())

    private fun existsWithoutFollowingLinks(file: File): Boolean = try {
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: SecurityException) {
        false
    }

    private fun isDirectoryWithoutFollowingLinks(file: File): Boolean =
        Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun isRegularFileWithoutFollowingLinks(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun hasSymbolicLinkComponent(file: File): Boolean {
        return try {
            val normalized = file.absoluteFile.toPath().normalize()
            var current = normalized.root
            for (segment in normalized) {
                current = if (current == null) segment else current.resolve(segment)
                if (Files.isSymbolicLink(current)) return true
            }
            false
        } catch (_: Exception) {
            true
        }
    }

    private fun Exception.displayMessage(): String = message ?: javaClass.simpleName
}
