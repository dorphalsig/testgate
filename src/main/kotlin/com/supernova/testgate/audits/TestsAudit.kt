package com.supernova.testgate.audits

import org.gradle.api.logging.Logger
import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 9) Tests (JVM results) — auditsTests
 *
 * Enforces basic test health from JUnit XML.
 * Failure rule: failures / testcases_executed > tolerance%.
 * - Executed tests exclude skipped/disabled/aborted (as reported via <skipped/>).
 * - Failures include <failure> and <error>.
 *
 * Required processing flow:
 * 1) Parse tool output  2) Generate findings  3) Aggregate  4) Invoke callback
 */
class TestsAudit(
    private val module: String,
    private val resultsDir: File,
    tolerancePercent: Int? = null,
    private val whitelistPatterns: List<String> = emptyList(),
    private val logger: Logger? = null,
    private val executedTaskNames: Collection<String> = emptyList()
) : Audit {

    private val tolerance = (tolerancePercent ?: 10).coerceAtLeast(0)

    override fun check(callback: (AuditResult) -> Unit) {
        // 1) Parse tool output
        val cases = parseAll(resultsDir)

        // 2) Generate findings (only for failed, after whitelist)
        val failed = cases.filter { it.status == StatusCase.FAILED && !isWhitelisted(it) }
        val findings = failed.map { it.toFinding() }

        // 3) Aggregate
        val executed = cases.count { it.status != StatusCase.SKIPPED && !isWhitelisted(it) }
        val failedCount = failed.size
        val ratio = if (executed == 0) 0.0 else failedCount.toDouble() / executed.toDouble()
        val status = if (ratio * 100.0 > tolerance) Status.FAIL else Status.PASS

        logger?.info(
            "[auditsTests] module={}, files={}, executed={}, failed={}, ratio={}%, tolerance={}%",
            module, cases.firstOrNull()?.sourceCount ?: 0, executed, failedCount, "%.2f".format(ratio * 100), tolerance
        )

        val result = AuditResult(
            module = module,
            name = "auditsTests",
            findings = findings,
            tolerance = tolerance,
            findingCount = failedCount,
            status = status
        )

        // 4) Invoke callback
        callback(result)
    }

    // -------------------- Parsing --------------------

    private fun parseAll(dir: File): List<TestCase> {
        if (!dir.exists() || !dir.isDirectory) {
            logMissingReports("JUnit XML directory not found", dir)
            return emptyList()
        }
        val xmlFiles = dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".xml") }?.toList().orEmpty()
        if (xmlFiles.isEmpty()) {
            throw IllegalStateException(missingXmlMessage(dir))
        }
        var count = 0
        val all = mutableListOf<TestCase>()
        for (f in xmlFiles) {
            val one = parseFile(f)
            count += 1
            // annotate first element with file count for logging; avoids extra fields
            if (all.isEmpty() && one.isNotEmpty()) {
                all += one.first().copy(sourceCount = xmlFiles.size)
                all += one.drop(1)
            } else {
                all += one
            }
        }
        return all
    }

    private fun logMissingReports(reason: String, dir: File) {
        val tasks = executedTaskNames.joinToString(", ").ifEmpty { "<none>" }
        logger?.warn("[auditsTests] module=$module -> $reason at ${dir.path}. Executed tasks: $tasks")
    }

    private fun missingXmlMessage(dir: File): String {
        val executed = executedTaskNames.takeIf { it.isNotEmpty() }?.joinToString(", ")
        return if (executed != null) {
            "No JUnit XML files found in: ${dir.path} (executed tasks: $executed)"
        } else {
            "No JUnit XML files found in: ${dir.path}"
        }
    }

    private fun parseFile(file: File): List<TestCase> {
        val doc = parseXml(file)
        val result = mutableListOf<TestCase>()
        val nodes = doc.getElementsByTagName("testcase")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            val classname = n.attr("classname") ?: ""
            val name = n.attr("name") ?: ""
            val skipped = n.firstChild("skipped")
            val failure = n.firstChild("failure")
            val error = n.firstChild("error")

            when {
                skipped != null -> result += TestCase(
                    classname, name, StatusCase.SKIPPED,
                    message = skipped.attr("message"),
                    stack = skipped.textLines()
                )
                failure != null -> result += TestCase(
                    classname, name, StatusCase.FAILED,
                    message = failure.attr("message") ?: "Test failed",
                    stack = failure.textLines()
                )
                error != null -> result += TestCase(
                    classname, name, StatusCase.FAILED,
                    message = error.attr("message") ?: "Test error",
                    stack = error.textLines()
                )
                else -> result += TestCase(classname, name, StatusCase.PASSED, message = null, stack = emptyList())
            }
        }
        return result
    }

    private fun parseXml(file: File): Document {
        try {
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            dbf.isNamespaceAware = false
            dbf.isValidating = false
            return dbf.newDocumentBuilder().parse(file)
        } catch (t: Throwable) {
            throw IllegalStateException("Failed to parse JUnit XML: ${file.path}", t)
        }
    }

    // -------------------- Findings & whitelist --------------------

    private fun TestCase.toFinding(): Finding {
        val head = message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val msg = if (head.isNotEmpty()) "${classname}#${name}: $head" else "${classname}#${name}: failed"
        return Finding(
            type = "JUnitTestFailure",
            filePath = null,
            line = null,
            severity = "error",
            message = msg,
            stacktrace = stack
        )
    }

    private fun isWhitelisted(tc: TestCase): Boolean {
        if (whitelistPatterns.isEmpty()) return false
        val cls = tc.classname.replace('.', '/')
        val symbol = "$cls#${tc.name}"
        return whitelistPatterns.any { p ->
            val pat = p.replace('.', '/')
            globMatch(pat, symbol) || globMatch(pat, cls)
        }
    }

    private fun globMatch(pattern: String, candidate: String): Boolean {
        // Use the platform PathMatcher with glob semantics; match on relative path form.
        return try {
            val pm = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            pm.matches(Paths.get(candidate))
        } catch (_: Throwable) {
            false
        }
    }

    // -------------------- Model --------------------

    private enum class StatusCase { PASSED, FAILED, SKIPPED }

    private data class TestCase(
        val classname: String,
        val name: String,
        val status: StatusCase,
        val message: String?,
        val stack: List<String>,
        // first element can carry source file count for logging (avoid extra fields elsewhere)
        val sourceCount: Int? = null
    )

    // DOM helpers (kept tiny; no external utils)
    private fun Node.attr(name: String): String? = attributes?.getNamedItem(name)?.nodeValue
    private fun Node.firstChild(tag: String): Node? {
        val children = childNodes ?: return null
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c.nodeName.equals(tag, ignoreCase = true)) return c
        }
        return null
    }
    private fun Node.textLines(): List<String> =
        (textContent ?: "").lines().map { it.trimEnd() }.dropWhile { it.isBlank() }.toList()
}
