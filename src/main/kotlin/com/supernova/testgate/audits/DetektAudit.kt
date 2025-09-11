package com.supernova.testgate.audits

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File

/**
 * Static code audit for Detekt XML reports.
 *
 * Behavior:
 * - Only severity "error" are counted (warnings/info ignored).
 * - Whitelist is PATH-based: module-relative path must match to exclude a file.
 * - Hard-fail: if any finding's ruleId is in [hardFailRuleIds], status=FAIL (no throw).
 * - Soft findings use tolerance: findings/files <= tolerancePercent%.
 * - Never throws on findings; only on real errors (missing/malformed report).
 */
class DetektAudit(
    private val module: String,
    private val reportXml: File,
    private val moduleDir: File,
    private val tolerancePercent: Int?,
    whitelistPatterns: List<String> = emptyList(),
    private val hardFailRuleIds: List<String> = emptyList(),
    private val logger: Logger
) : Audit {

    private val whitelist = WhitelistMatcher(whitelistPatterns)

    override fun check(callback: (AuditResult) -> Unit) {
        val doc = parseReport(reportXml)
        val counts = collectFindings(doc)

        val files = scanSourceFiles(moduleDir).let { if (it <= 0) 1 else it }
        val tol = tolerancePercent ?: 10

        val status = when {
            counts.hard > 0 -> Status.FAIL
            (counts.soft.toDouble() / files.toDouble()) <= (tol.toDouble() / 100.0) -> Status.PASS
            else -> Status.FAIL
        }

        logger.info(
            "[DetektAudit] module={} hard={} soft={} files={} tol%={} -> {}",
            module, counts.hard, counts.soft, files, tol, status
        )

        callback(
            AuditResult(
                module = module,
                name = "Detekt",
                findings = counts.findings,
                tolerance = tol,
                findingCount = counts.findings.size,
                status = status
            )
        )
    }

    private fun parseReport(file: File): Document = try {
        if (!file.exists()) {
            throw GradleException("Detekt report not found at ${file.absolutePath}")
        }
        xml(file)
    } catch (e: GradleException) {
        throw e
    } catch (t: Throwable) {
        throw GradleException("Failed to parse Detekt report at ${file.absolutePath}", t)
    }

    private data class Counts(val findings: List<Finding>, val hard: Int, val soft: Int)

    private fun collectFindings(doc: Document): Counts {
        val out = mutableListOf<Finding>()
        var hard = 0
        var soft = 0

        val fileNodes = doc.getElementsByTagName("file")
        for (i in 0 until fileNodes.length) {
            val fileEl = fileNodes.item(i) as? Element ?: continue
            val filePath = fileEl.getAttribute("name")
            val relPath = moduleRelativePath(moduleDir, filePath)
            if (whitelist.matchesPath(relPath)) continue // file excluded by whitelist

            val errNodes = fileEl.getElementsByTagName("error")
            for (j in 0 until errNodes.length) {
                val err = errNodes.item(j) as? Element ?: continue
                val severity = err.getAttribute("severity")
                if (!severity.equals("error", ignoreCase = true)) continue

                val line = err.getAttribute("line").toIntOrNull()
                val msg = err.getAttribute("message").orEmpty()
                val ruleId = ruleIdFromSourceOrMessage(err, msg)

                out += Finding(
                    type = "detekt.$ruleId",
                    filePath = filePath, // keep original for UX; matching used relPath
                    line = line,
                    severity = severity,
                    message = msg
                )
                if (ruleId in hardFailRuleIds) hard++ else soft++
            }
        }
        return Counts(out, hard, soft)
    }

    /**
     * Prefer `<error source="detekt.UnusedPrivateMember">` → "UnusedPrivateMember".
     * Fall back to message formats if `source` is missing.
     */
    private fun ruleIdFromSourceOrMessage(err: Element, message: String): String {
        val src = err.getAttribute("source")
        if (src.isNotBlank()) {
            val simple = src.substringAfterLast('.')
            return if (simple.startsWith("detekt.", ignoreCase = true)) simple.removePrefix("detekt.") else simple
        }
        // Fallbacks (rare): "[RuleId] ..." or "RuleId: ..."
        Regex("""^\[([^]]+)]""").find(message)?.let { return it.groupValues[1] }
        val colon = message.substringBefore(':', missingDelimiterValue = "").trim()
        return if (colon.isNotEmpty()) colon else "Unknown"
    }
}
