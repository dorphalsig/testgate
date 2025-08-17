// src/main/kotlin/com/supernova/testgate/audits/AndroidLintAuditTask.kt
package com.supernova.testgate.audits

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * AndroidLintAuditTask:
 * - Implements [Audit] so aggregators call [check] to receive a CheckpointResult.
 * - Is a Gradle Task so it participates in inputs/outputs, caching, and graph wiring.
 *
 * Contract types: Status, Finding, CheckpointResult, Audit. :contentReference[oaicite:1]{index=1}
 */
@DisableCachingByDefault(because = "Enable explicit inputs/outputs if you want remote cache.")
abstract class AndroidLintAuditTask : DefaultTask(), Audit {

    /** Lint XML report; defaults to <build>/reports/lint-results-debug.xml */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val reportFile: Provider<RegularFile> =
        project.layout.buildDirectory.file("reports/lint-results-debug.xml")

    /** Number of allowed error findings before FAIL (default 0). */
    @get:Input
    abstract val tolerance: Property<Int>

    /** Comma-separated import-like FQCN wildcards to exclude (e.g., com.foo.*, a.b.C). */
    @get:Input
    abstract val exceptionsCsv: Property<String>

    /** Last computed result, available for aggregators after execution. */
    @get:Internal
    var lastResult: CheckpointResult? = null
        private set

    init {
        tolerance.convention(
            (project.findProperty("androidLintTolerance") as? Number)?.toInt() ?: 0
        )
        exceptionsCsv.convention(project.findProperty("androidLintExceptions") as? String ?: "")
    }

    @TaskAction
    fun runAudit() {
        lastResult = compute()
    }

    // ---- Audit.check(callback) required by the aggregator contract ----
    override fun check(callback: (CheckpointResult) -> Unit) {
        // If the task already ran in this build, reuse; otherwise compute on demand.
        val result = lastResult ?: compute()
        lastResult = result
        callback(result)
    }

    // ---------------- Core computation (shared by runAudit() and check()) ----------------
    private fun compute(): CheckpointResult {
        val xml = reportFile.get().asFile
        if (!xml.exists()) {
            throw GradleException("Android Lint report file not found: ${xml.path}")
        }
        val exceptions = readExceptions(exceptionsCsv.orNull)
        val allErrors = parseErrorFindings(xml)
        val filtered = allErrors.filterNot { f ->
            val fqcn = f.filePath?.let { deriveFqcn(it) }
            fqcn != null && exceptions.any { it.matches(fqcn) }
        }
        val status = if (filtered.size <= tolerance.get()) Status.PASS else Status.FAIL
        return CheckpointResult(
            module = project.name,
            name = "androidlint",
            findings = filtered,
            tolerance = tolerance.get(),
            findingCount = filtered.size,
            status = status
        )
    }

    // ---------------- Parsing (errors only) ----------------
    internal fun parseErrorFindings(xmlFile: File): List<Finding> {
        val doc = newDocument(xmlFile)
        val xPath = XPathFactory.newInstance().newXPath()
        val nodes = xPath.evaluate("//issue", doc, XPathConstants.NODESET) as org.w3c.dom.NodeList
        val out = ArrayList<Finding>(nodes.length)
        for (i in 0 until nodes.length) {
            val issue = nodes.item(i) as Element
            val rawSeverity = issue.getAttribute("severity")
            if (!isErrorSeverity(rawSeverity)) continue

            val id = issue.getAttribute("id")
            val message = issue.getAttribute("message").ifBlank { id }
            val locs =
                xPath.evaluate(".//location", issue, XPathConstants.NODESET) as org.w3c.dom.NodeList
            if (locs.length == 0) continue
            val primary = locs.item(0) as Element

            val filePath = primary.getAttribute("file")
            val line = primary.getAttribute("line").toIntOrNull()

            val trail = buildList {
                for (j in 1 until locs.length) {
                    val e = locs.item(j) as Element
                    val f = e.getAttribute("file")
                    val ln = e.getAttribute("line")
                    val col = e.getAttribute("column")
                    val msg = e.getAttribute("message")
                    val s = buildString {
                        append(f)
                        if (ln.isNotBlank()) append(":").append(ln)
                        if (col.isNotBlank()) append(":").append(col)
                        if (msg.isNotBlank()) append(" — ").append(msg)
                    }
                    if (s.isNotBlank()) add(s)
                }
            }

            out += Finding(
                type = "androidlint",
                filePath = filePath,
                line = line,
                severity = rawSeverity.ifBlank { "Error" },
                message = message,
                stacktrace = trail
            )
        }
        return out
    }

    internal fun isErrorSeverity(sev: String?): Boolean =
        when (sev?.trim()?.lowercase()) {
            null -> true
            "fatal", "error" -> true
            "warning", "informational", "information", "info", "ignore" -> false
            else -> false
        }

    internal fun newDocument(file: File): Document {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isExpandEntityReferences = false
            } catch (_: Exception) { /* best effort hardening */
            }
            isNamespaceAware = false
            isValidating = false
        }
        return dbf.newDocumentBuilder().parse(file)
    }

    // ---------------- Whitelist helpers ----------------
    internal fun readExceptions(csv: String?): List<ImportWildcard> =
        csv?.split(',')?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }?.map(::ImportWildcard)
            ?: emptyList()

    /** Derive FQCN from a typical src path: .../src/<set>/(java|kotlin)/a/b/C.kt → a.b.C */
    internal fun deriveFqcn(pathLike: String): String? {
        val path = pathLike.replace('\\', '/')
        val srcIdx = path.indexOf("/src/")
        if (srcIdx == -1) return null
        val segment = path.substring(srcIdx)
        val java = segment.substringAfter("/java/", "")
        val kt = segment.substringAfter("/kotlin/", "")
        val within = if (java.isNotEmpty()) java else if (kt.isNotEmpty()) kt else return null
        val noExt = within.substringBeforeLast('.', "")
        val fqcn = noExt.replace('/', '.')
        return fqcn.ifBlank { null }
    }
}


class ImportWildcard(pattern: String) {
    // Convert "com.acme.*" → "com/acme/*"
    private val globPattern = pattern.replace('.', '/')
    private val matcher = FileSystems.getDefault().getPathMatcher("glob:$globPattern")

    fun matches(fqcn: String): Boolean {
        val pathLike = fqcn.replace('.', '/')
        return matcher.matches(Paths.get(pathLike))
    }
}
