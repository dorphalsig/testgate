package com.supernova.testgate.audits

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.w3c.dom.Element
import java.io.File

/**
 * Static code audit for Detekt XML reports.
 *
 * Rules:
 * - Findings NEVER throw: we always invoke the callback with AuditResult (PASS/FAIL).
 * - Processing errors (missing/malformed XML, IO) DO throw GradleException with cause.
 * - Only severity "error" are counted as findings.
 * - Whitelist is FILE-PATH based (Detekt has no reliable class info in XML). If a file matches any pattern, all its findings are ignored.
 * - Hard-fail rule ids are configurable (property knob via constructor). Presence => status=FAIL (no throw).
 * - Soft issues use tolerance (default 10%).
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

        val fileCountRaw = scanSourceFiles(moduleDir)
        val fileCount = if (fileCountRaw <= 0) 1 else fileCountRaw
        val tol = tolerancePercent ?: 10

        val status = when {
            counts.hard > 0 -> Status.FAIL
            (counts.soft.toDouble() / fileCount.toDouble()) <= (tol.toDouble() / 100.0) -> Status.PASS
            else -> Status.FAIL
        }

        logger.info(
            "[DetektAudit] module={} hard={} soft={} files={} tol%={} -> {}",
            module, counts.hard, counts.soft, fileCount, tol, status
        )

        callback(
            AuditResult(
                module = module,
                name = "DetektAudit",
                findings = counts.findings,
                tolerance = tol,
                findingCount = counts.findings.size,
                status = status
            )
        )
    }

    private fun parseReport(file: File) = try {
        if (!file.exists()) throw GradleException("Static code audit misconfigured: Detekt report not found at ${file.absolutePath}")
        xml(file)
    } catch (e: GradleException) {
        throw e
    } catch (t: Throwable) {
        throw GradleException("Failed to parse Detekt report at ${file.absolutePath}", t)
    }

    private data class Counts(val findings: List<Finding>, val hard: Int, val soft: Int)

    private fun collectFindings(doc: org.w3c.dom.Document): Counts {
        val out = mutableListOf<Finding>()
        var hard = 0
        var soft = 0
        val fileNodes = doc.getElementsByTagName("file")
        for (i in 0 until fileNodes.length) {
            val fileEl = fileNodes.item(i) as? Element ?: continue
            val filePath = fileEl.getAttribute("name")
            if (whitelist.matchesPath(filePath)) continue // entire file excluded

            val errNodes = fileEl.getElementsByTagName("error")
            for (j in 0 until errNodes.length) {
                val err = errNodes.item(j) as? Element ?: continue
                val sev = err.getAttribute("severity")
                if (!sev.equals("error", ignoreCase = true)) continue

                val lineAttr = err.getAttribute("line")
                val line = lineAttr.toIntOrNull()
                val msg = err.getAttribute("message").ifBlank { "" }
                val type = extractRuleIdFromMessage(msg)

                out += Finding(
                    type = type,
                    filePath = filePath,
                    line = line,
                    severity = sev,
                    message = msg
                )
                if (type in hardFailRuleIds) hard++ else soft++
            }
        }
        return Counts(out, hard, soft)
    }
}