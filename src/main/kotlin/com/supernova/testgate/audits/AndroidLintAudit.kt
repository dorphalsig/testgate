package com.supernova.testgate.audits

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.w3c.dom.Element
import java.io.File

/**
 * Static code audit for Android Lint XML reports (format="6").
 *
 * Rules:
 * - Findings NEVER throw: we always invoke the callback with AuditResult (PASS/FAIL).
 * - Processing errors (missing/malformed XML, IO) DO throw GradleException with cause.
 * - Only severity Error/Fatal are counted as findings.
 * - Whitelist is FILE-PATH based (same semantics as Detekt): if a file matches, its findings are ignored.
 * - 1 finding = 1 <issue> × 1 <location> (unfold multiple locations).
 */
class AndroidLintAudit(
    private val module: String,
    private val reportXml: File,
    private val moduleDir: File,
    private val tolerancePercent: Int?,
    whitelistPatterns: List<String>,
    private val logger: Logger
) : Audit {

    private val whitelist = WhitelistMatcher(whitelistPatterns)

    override fun check(callback: (AuditResult) -> Unit) {
        val doc = parseReport(reportXml)
        val findings = collectFindings(doc)

        val fileCountRaw = scanSourceFiles(moduleDir)
        val fileCount = if (fileCountRaw <= 0) 1 else fileCountRaw
        val tol = tolerancePercent ?: 10

        val errorCount = findings.size
        val pass = (errorCount.toDouble() / fileCount.toDouble()) <= (tol.toDouble() / 100.0)
        val status = if (pass) Status.PASS else Status.FAIL

        logger.info(
            "[AndroidLintAudit] module={} errors={} files={} tol%={} -> {}",
            module, errorCount, fileCount, tol, status
        )

        callback(
            AuditResult(
                module = module,
                name = "AndroidLintAudit",
                findings = findings,
                tolerance = tol,
                findingCount = errorCount,
                status = status
            )
        )
    }

    private fun parseReport(file: File) = try {
        if (!file.exists()) throw GradleException("Static code audit misconfigured: Android Lint report not found at ${file.absolutePath}")
        xml(file)
    } catch (e: GradleException) {
        throw e
    } catch (t: Throwable) {
        throw GradleException("Failed to parse Android Lint report at ${file.absolutePath}", t)
    }

    private fun collectFindings(doc: org.w3c.dom.Document): List<Finding> {
        val out = mutableListOf<Finding>()
        val issues = doc.getElementsByTagName("issue")
        for (i in 0 until issues.length) {
            val issueEl = issues.item(i) as? Element ?: continue
            val severity = issueEl.getAttribute("severity")
            if (!isErrorOrFatal(severity)) continue
            val type = issueEl.getAttribute("id").ifBlank { "Unknown" }
            val msg = issueEl.getAttribute("message").ifBlank { "" }

            val locs = issueEl.getElementsByTagName("location")
            if (locs.length == 0) continue

            for (j in 0 until locs.length) {
                val loc = locs.item(j) as? Element ?: continue
                val file = loc.getAttribute("file")
                val rel = moduleRelativePath(moduleDir, file)
                if (whitelist.matchesPath(rel)) continue
                val line = loc.getAttribute("line").toIntOrNull()
                out += Finding(
                    type = type,
                    filePath = file,
                    line = line,
                    severity = severity,
                    message = msg
                )
            }
        }
        return out
    }

    private fun isErrorOrFatal(sev: String?): Boolean {
        if (sev == null) return false
        val s = sev.lowercase()
        return s == "error" || s == "fatal"
    }
}
