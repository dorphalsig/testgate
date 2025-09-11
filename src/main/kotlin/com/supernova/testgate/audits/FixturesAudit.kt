package com.supernova.testgate.audits

import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Audit 8) Fixtures (presence & size)
 *
 * Rules:
 *  - Hard-coded glob: src/test/resources/**/*.json
 *  - Presence: require >= 1 fixture unless module is whitelisted
 *  - Size window: minBytes <= file.size <= maxBytes
 *      * file.size < minBytes  -> FixtureTooSmall (warning)
 *      * file.size > maxBytes  -> FixtureOversize (warning)
 *  - Pass if: (tooSmall + oversize) / total <= tolerancePercent AND presence satisfied
 *
 * Processing flow (required):
 *  1) Parse "tool output" (filesystem scan)
 *  2) Generate findings
 *  3) Aggregate into AuditResult
 *  4) Invoke callback provided to check()
 */
class FixturesAudit(
    private val module: String,
    private val moduleDir: File,
    private val tolerancePercent: Int? = 10,
    private val minBytes: Int = 256,
    private val maxBytes: Int = 8192,
    private val whitelistPatterns: List<String> = emptyList()
) : Audit {

    override fun check(callback: (AuditResult) -> Unit) {
        val tolerance = (tolerancePercent ?: 10).coerceAtLeast(0)
        val whitelisted = isModuleWhitelisted()
        val fixtures = collectFixtureFiles(moduleDir)

        val findings = mutableListOf<Finding>()
        presenceFinding(total = fixtures.size, whitelisted = whitelisted)?.let { findings += it }
        findings += sizeFindings(fixtures)

        val status = computeStatus(findings, fixtures.size, tolerance)

        callback(
            AuditResult(
                module = module,
                name = "auditsFixtures",
                findings = findings,
                tolerance = tolerance,
                findingCount = findings.size,
                status = status
            )
        )
    }

    // --- helpers ---

    private fun isModuleWhitelisted(): Boolean {
        if (whitelistPatterns.isEmpty()) return false
        val matcher = WhitelistMatcher(whitelistPatterns)
        return matcher.matchesPath(moduleDir.absolutePath)
    }

    private fun collectFixtureFiles(moduleDir: File): List<Path> {
        val root = moduleDir.toPath().resolve("src").resolve("test").resolve("resources")
        if (!Files.exists(root)) return emptyList()
        val out = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .forEach { p ->
                    val name = p.fileName?.toString()?.lowercase() ?: ""
                    if (name.endsWith(".json")) {
                        out.add(p)
                    }
                }
        }
        return out
    }

    private fun presenceFinding(total: Int, whitelisted: Boolean): Finding? {
        if (whitelisted || total > 0) return null
        return Finding(
            type = "MissingFixture",
            filePath = moduleDir.absolutePath,
            line = null,
            severity = "error",
            message = "No JSON fixtures found (glob=src/test/resources/**/*.json)"
        )
    }

    private fun sizeFindings(files: List<Path>): List<Finding> {
        if (files.isEmpty()) return emptyList()
        val list = ArrayList<Finding>()
        files.forEach { p ->
            val size = safeSize(p)
            if (size < minBytes) {
                list += Finding(
                    type = "FixtureTooSmall",
                    filePath = p.toString(),
                    line = null,
                    severity = "warning",
                    message = "Fixture is too small (size=${size} B, min=${minBytes} B)"
                )
            } else if (size > maxBytes) {
                list += Finding(
                    type = "FixtureOversize",
                    filePath = p.toString(),
                    line = null,
                    severity = "warning",
                    message = "Fixture exceeds limit (size=${size} B, max=${maxBytes} B)"
                )
            }
        }
        return list
    }

    private fun computeStatus(findings: List<Finding>, total: Int, tolerance: Int): Status {
        if (findings.any { it.type == "MissingFixture" }) return Status.FAIL
        val violations = findings.count { it.type == "FixtureTooSmall" || it.type == "FixtureOversize" }
        if (total == 0) return Status.PASS // only possible when whitelisted
        val ratio = (violations * 100.0) / total
        return if (ratio <= tolerance) Status.PASS else Status.FAIL
    }

    private fun safeSize(p: Path): Long {
        // Try fast NIO first; fall back to java.io.File for edge cases where NIO
        // signals NoSuchFileException even though the file exists (seen on some FSs).
        return try {
            Files.size(p)
        } catch (e: NoSuchFileException) {
            val f = p.toFile()
            if (f.exists()) f.length()
            else throw IllegalStateException("Fixture not found while scanning: $p", e)
        } catch (e: SecurityException) {
            // True error per policy — fail the build with context.
            throw IllegalStateException("Access denied reading fixture size: $p", e)
        } catch (e: Exception) {
            // Other true errors — fail with preserved cause.
            throw IllegalStateException("Failed to read size of fixture: $p", e)
        }
    }
}
