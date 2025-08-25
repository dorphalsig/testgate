// File: src/main/kotlin/com/supernova/testgate/audits/CoverageBranchesAudit.kt
package com.supernova.testgate.audits

import org.w3c.dom.Element
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Audit 10) Coverage (JaCoCo — branches): enforces a minimum module branch coverage.
 *
 * Behavior:
 * - Parses JaCoCo XML and aggregates BRANCH counters at class level.
 * - Honours whitelist patterns (FQCN dot or path glob) via WhitelistMatcher.
 * - Module coverage % (rounded to 1 decimal) is reported in AuditResult.findingCount.
 * - On FAIL, emits one finding per non-whitelisted class below the threshold.
 *
 * Clean-code constraints: small methods, early returns, no deep nesting.
 */
class CoverageBranchesAudit(
    private val module: String,
    private val reportXml: File,
    private val moduleDir: File,
    private val thresholdPercent: Int?,
    private val whitelistPatterns: List<String>
) : Audit {

    override fun check(callback: (AuditResult) -> Unit) {
        val matcher = WhitelistMatcher(whitelistPatterns.filter { it.isNotBlank() })
        val classes = parseJacocoClasses(reportXml)

        val considered = classes.filterNot { matcher.matchesFqcnOrSymbol(it.fqcn) }
        val totals = sumTotals(considered)
        val totalPct = percent(totals.covered, totals.missed)

        val th = effectiveThreshold()
        val offenders = considered
            .asSequence()
            .filter { (it.covered + it.missed) > 0 } // only classes that actually have branches
            .filter { percent(it.covered, it.missed) < th }
            .sortedBy { percent(it.covered, it.missed) } // worst first
            .map { it.toFinding(moduleDir, th) }
            .toList()

        val status = if (totalPct < th) Status.FAIL else Status.PASS
        val findings = if (status == Status.FAIL) offenders else emptyList()
        val result = AuditResult(
            module = module,
            name = "Coverage (branches)",
            findings = findings,
            tolerance = 0,
            status = status,
            findingCount = round1(totalPct)
        )

        callback(result)
    }

    // -------------------- Parsing --------------------

    private fun parseJacocoClasses(file: File): List<ClassStat> {
        if (!file.exists()) {
            throw IllegalStateException("JaCoCo report not found at: ${file.absolutePath}")
        }
        val doc = xml(file).also { it.normalizeDocument() }

        val list = mutableListOf<ClassStat>()
        val classNodes = doc.getElementsByTagName("class")
        for (i in 0 until classNodes.length) {
            val node = classNodes.item(i)
            if (node is Element) {
                val rawName = node.getAttribute("name") // e.g., a/b/C$D
                if (rawName.isNullOrBlank()) continue
                val fqcn = rawName.replace('/', '.')
                val (missed, covered) = readBranchCounter(node)
                val sourceFile = node.getAttribute("sourcefilename")
                list += ClassStat(
                    fqcn = fqcn,
                    missed = missed,
                    covered = covered,
                    sourcePath = buildSourcePath(fqcn, sourceFile)
                )
            }
        }
        return list
    }

    private fun readBranchCounter(classEl: Element): Pair<Long, Long> {
        // Only consider CLASS-LEVEL counters (direct children of <class>),
        // not method-level counters nested inside <method>.
        val children = classEl.childNodes
        for (k in 0 until children.length) {
            val node = children.item(k)
            if (node is Element && node.tagName == "counter" && node.getAttribute("type") == "BRANCH") {
                val missed = node.getAttribute("missed").toLongOrNull() ?: 0L
                val covered = node.getAttribute("covered").toLongOrNull() ?: 0L
                return missed to covered
            }
        }
        // If no class-level BRANCH counter is present, treat as 0/0.
        return 0L to 0L
    }

    private fun buildSourcePath(fqcn: String, sourceFile: String?): String? {
        if (sourceFile.isNullOrBlank()) return null
        val pkg = fqcn.substringBeforeLast('.', missingDelimiterValue = "").replace('.', '/')
        return if (pkg.isBlank()) sourceFile else "$pkg/$sourceFile"
    }

    // -------------------- Compute --------------------

    private fun sumTotals(classes: List<ClassStat>): Totals {
        var missed = 0L
        var covered = 0L
        classes.forEach {
            missed += it.missed
            covered += it.covered
        }
        return Totals(missed = missed, covered = covered)
    }

    private fun effectiveThreshold(): Double {
        val t = thresholdPercent ?: 70
        return min(100.0, max(0.0, t.toDouble()))
    }

    private fun percent(covered: Long, missed: Long): Double {
        val denom = covered + missed
        if (denom <= 0) return 0.0
        return (covered.toDouble() / denom.toDouble()) * 100.0
    }

    private fun round1(value: Double): Double {
        return (round(value * 10.0) / 10.0)
    }

    // -------------------- Data & helpers --------------------

    private data class ClassStat(
        val fqcn: String,
        val missed: Long,
        val covered: Long,
        val sourcePath: String?
    ) {
        fun toFinding(moduleDir: File, threshold: Double): Finding {
            val denom = covered + missed
            val pct = if (denom > 0) (covered.toDouble() / denom.toDouble()) * 100.0 else 0.0
            val msg =
                "${fqcn} — ${"%.1f".format(pct)}% < ${"%.0f".format(threshold)}% (missed=$missed, covered=$covered)"
            val fp = sourcePath?.let { moduleRelativePath(moduleDir, it) }
            return Finding(
                type = "ClassBelowThreshold",
                filePath = fp,
                line = null,
                severity = "error",
                message = msg
            )
        }
    }

    private data class Totals(val missed: Long, val covered: Long)
}
