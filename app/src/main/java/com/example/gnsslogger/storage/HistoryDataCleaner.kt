package com.example.gnsslogger.storage

import java.io.File

data class CleanupScanResult(
    val fileCount: Int,
    val totalBytes: Long,
)

data class CleanupFailure(
    val path: String,
    val reason: String,
)

data class CleanupResult(
    val deletedFileCount: Int,
    val deletedDirCount: Int,
    val freedBytes: Long,
    val failedFiles: List<CleanupFailure>,
)

class HistoryDataCleaner(
    roots: List<File>,
    excludedFiles: Set<File> = emptySet(),
) {
    private val canonicalRoots = roots.mapNotNull { it.safeCanonicalFile() }.distinctBy { it.absolutePath }
    private val canonicalExcludedFiles = excludedFiles.mapNotNull { it.safeCanonicalFile()?.absolutePath }.toSet()

    fun scan(): CleanupScanResult {
        val files = collectCleanableFiles()
        return CleanupScanResult(
            fileCount = files.size,
            totalBytes = files.sumOf { it.length().coerceAtLeast(0L) },
        )
    }

    fun clean(): CleanupResult {
        val cleanableFiles = collectCleanableFiles()
        var deletedFileCount = 0
        var freedBytes = 0L
        val failures = mutableListOf<CleanupFailure>()

        cleanableFiles.forEach { file ->
            val size = file.length().coerceAtLeast(0L)
            try {
                if (file.delete()) {
                    deletedFileCount++
                    freedBytes += size
                } else if (file.exists()) {
                    failures.add(CleanupFailure(file.absolutePath, "delete returned false"))
                }
            } catch (e: Exception) {
                failures.add(CleanupFailure(file.absolutePath, e.message ?: e.javaClass.simpleName))
            }
        }

        val deletedDirCount = deleteEmptyHistoryDirs(failures)
        return CleanupResult(
            deletedFileCount = deletedFileCount,
            deletedDirCount = deletedDirCount,
            freedBytes = freedBytes,
            failedFiles = failures,
        )
    }

    private fun collectCleanableFiles(): List<File> =
        canonicalRoots
            .filter { it.exists() && it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .onEnter { dir -> isWithinAnyRoot(dir) && !isExcluded(dir) }
                    .filter { it.isFile && isWithinRoot(it, root) && !isExcluded(it) && isCleanableFile(it) }
                    .toList()
            }
            .distinctBy { it.safeCanonicalFile()?.absolutePath ?: it.absolutePath }

    private fun deleteEmptyHistoryDirs(failures: MutableList<CleanupFailure>): Int {
        var deletedDirCount = 0
        val dirs = canonicalRoots
            .filter { it.exists() && it.isDirectory }
            .flatMap { root ->
                root.walkBottomUp()
                    .filter { dir -> dir.isDirectory && dir != root && isWithinRoot(dir, root) && !isExcluded(dir) }
                    .toList()
            }
            .distinctBy { it.safeCanonicalFile()?.absolutePath ?: it.absolutePath }

        dirs.forEach { dir ->
            if (!isHistoryDirectory(dir)) return@forEach
            val children = dir.listFiles()
            if (children != null && children.isNotEmpty()) return@forEach
            try {
                if (dir.delete()) {
                    deletedDirCount++
                } else if (dir.exists()) {
                    failures.add(CleanupFailure(dir.absolutePath, "delete directory returned false"))
                }
            } catch (e: Exception) {
                failures.add(CleanupFailure(dir.absolutePath, e.message ?: e.javaClass.simpleName))
            }
        }
        return deletedDirCount
    }

    private fun isCleanableFile(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".csv") ||
            name.endsWith(".nmea") ||
            name.endsWith(".kml")
    }

    private fun isHistoryDirectory(dir: File): Boolean {
        val name = dir.name.lowercase()
        return name.startsWith("session_") ||
            name == "gnsslogger" ||
            name == "exports" ||
            name.matches(Regex("\\d{8}")) ||
            dir.listFiles()?.all { child ->
                child.isDirectory || isCleanableFile(child)
            } == true
    }

    private fun isWithinAnyRoot(file: File): Boolean =
        canonicalRoots.any { root -> isWithinRoot(file, root) }

    private fun isWithinRoot(file: File, root: File): Boolean {
        val canonicalFile = file.safeCanonicalFile() ?: return false
        return canonicalFile.toPath().startsWith(root.toPath())
    }

    private fun isExcluded(file: File): Boolean {
        val canonicalFile = file.safeCanonicalFile()?.absolutePath ?: return false
        return canonicalFile in canonicalExcludedFiles
    }

    private fun File.safeCanonicalFile(): File? =
        try {
            canonicalFile
        } catch (_: Exception) {
            null
        }
}
