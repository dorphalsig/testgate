package com.supernova.testgate.audits
import org.gradle.api.logging.Logger
import java.io.File

/**
 * SQL / FTS Safety audit:
 *  - Ban @RawQuery and SupportSQLiteQuery (whitelist-exempt).
 *  - Ban complex SQL in @Query: JOIN|UNION|WITH|CREATE|ALTER|INSERT|UPDATE|DELETE (whitelist-exempt).
 *  - FTS lock: ban @Fts5 and require @Fts4 if FTS is present (no whitelist).
 *  - Rails guard: If selecting FROM RailEntry, require ORDER BY position and forbid ORDER BY popularity (always on).
 *
 * Tolerance rule: PASS if findings * 100 <= tolerancePercent * scannedFiles
 */
class SqlFtsAudit(
    private val module: String,
    private val moduleDir: File,
    tolerancePercent: Int?,
    private val whitelistPatterns: List<String>,
    private val logger: Logger
) : Audit {

    private val name = "auditsSqlFts"
    private val tolerance: Int = (tolerancePercent ?: 0).coerceIn(0, 100)
    private val whitelist = WhitelistMatcher(whitelistPatterns)

    override fun check(callback: (AuditResult) -> Unit) {
        require(moduleDir.exists() && moduleDir.isDirectory) {
            "Module directory not found or not a directory: ${moduleDir.absolutePath}"
        }
        val srcFiles = scanSources(moduleDir)
        val results = mutableListOf<Finding>()
        var sawFts4 = false
        var sawAnyFts = false

        srcFiles.forEach { file ->
            val rel = relative(file)
            val text = safeRead(file) ?: return@forEach
            val queries = SqlScanner.extractQueries(text)
            val ftsFlags = SqlScanner.scanFtsFlags(text)
            sawFts4 = sawFts4 || ftsFlags.seenFts4
            sawAnyFts = sawAnyFts || ftsFlags.seenAny

            if (!whitelist.matchesPath(rel)) {
                results += SqlScanner.findRawQueryIssues(text, rel)
                results += SqlScanner.findSupportSQLiteQueryIssues(text, rel)
            }
            if (!whitelist.matchesPath(rel)) {
                results += SqlScanner.findComplexQueryIssues(queries, rel)
            }
            // Rails guard is always on (non-configurable)
            results += SqlScanner.findRailsGuardIssues(queries, rel)
            // FTS5 is globally banned (no whitelist)
            results += SqlScanner.findFts5Issues(text, rel)
        }

        // FTS requirement: @Fts4 must exist if any FTS is used
        results += SqlScanner.finalizeFtsFindings(sawAnyFts, sawFts4)

        val findingCount = results.size
        val scanned = (srcFiles.size).coerceAtLeast(1)
        val pass = findingCount * 100 <= tolerance * scanned
        val status = if (pass) Status.PASS else Status.FAIL

        val auditResult = AuditResult(
            module = module,
            name = name,
            findings = results,
            tolerance = tolerance,
            findingCount = findingCount,
            status = status
        )
        logger.info("[$name] module=$module files=${srcFiles.size} findings=$findingCount tolerance=$tolerance status=$status")
        callback(auditResult)
    }

    private fun relative(file: File): String =
        file.absolutePath.replace(File.separatorChar, '/')

    private fun scanSources(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.path.contains("${File.separator}src${File.separator}") }
            .filter { it.extension.equals("kt", true) || it.extension.equals("java", true) }
            .toList()

    private fun safeRead(file: File): String? = try {
        file.readText()
    } catch (e: Exception) {
        throw IllegalStateException("Failed to read source file: ${file.absolutePath}", e)
    }
}
