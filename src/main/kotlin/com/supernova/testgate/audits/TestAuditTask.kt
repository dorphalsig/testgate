package com.supernova.testgate.audits

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.w3c.dom.Element

/**
 * Hidden task that parses JUnit XML reports and converts failures/errors into Findings.
 * - Always targets unit tests:
 *    * Android modules: testDebugUnitTest (wired externally)
 *    * JVM modules:     test               (wired externally)
 * - Cache-friendly via Gradle File APIs.
 * - Applies whitelist (exceptions) using PathMatcher with "." as logical separator.
 * - Uses XPath to select failing/erroring testcases.
 *
 * Responsibility: **Only** capture errors from files and emit a CheckpointResult via callback if provided.
 */
@DisableCachingByDefault(because = "Results depend on upstream test execution, not stable for caching at task level")
abstract class TestAuditTask : DefaultTask() {

    init {
        // Hidden task: no group/description on purpose
        isEnabled = true
    }

    /**
     * JUnit XML report files to parse (wired from the upstream Test task's reports.junitXml.outputLocation).
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val junitXml: ConfigurableFileCollection

    /**
     * Compiled test classes dirs from the upstream Test task.
     * Used only to guard against misconfiguration (non-empty classes but no XML produced).
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testClassesDirs: ConfigurableFileCollection

    /**
     * The exact upstream test task name (e.g., "test" or "testDebugUnitTest") for error messages.
     */
    @get:Input
    abstract val testTaskName: Property<String>

    /**
     * Whitelist patterns (exceptions), import-like wildcards with '.' as separator (e.g., com.example.*, org.foo.BarTest).
     */
    @get:Input
    abstract val exceptions: ListProperty<String>

    /**
     * Max allowed number of findings after whitelist filter.
     */
    @get:Input
    abstract val tolerance: Property<Int>

    /**
     * Optional callback to handle the result (e.g., ResultCollector.record(result)).
     * This is intentionally not an input to caching.
     */
    @get:Internal
    var onResult: ((CheckpointResult) -> Unit)? = null

    fun setResultCallback(callback: (CheckpointResult) -> Unit) {
        this.onResult = callback
    }

    @TaskAction
    fun runAudit() {
        // Read defaults from project properties if not explicitly set.
        val tol = if (tolerance.isPresent) tolerance.get() else (project.findProperty("testgate.tolerance.tests") as? String)?.toIntOrNull() ?: 0

        val mergedExceptions = mutableListOf<String>()
        mergedExceptions += exceptions.orNull ?: emptyList()
        (project.findProperty("testgate.exceptions.tests") as? String)?.let { if (it.isNotBlank()) mergedExceptions += it.split(',').map(String::trim) }
        (project.findProperty("testgate.exceptions") as? String)?.let { if (it.isNotBlank()) mergedExceptions += it.split(',').map(String::trim) }

        val xmlFiles: List<File> = junitXml.files.filter { it.isFile && it.extension.lowercase() == "xml" }

        // Guardrail to avoid silent PASS when pointed at the wrong directory:
        val hasAnyTestClasses = containsAnyClassFile(testClassesDirs)
        if (hasAnyTestClasses && xmlFiles.isEmpty()) {
            val where = junitXml.files.firstOrNull()?.parentFile ?: project.layout.buildDirectory.get().asFile
            val taskName = testTaskName.orNull ?: "<unknown test task>"
            throw GradleException(
                "TestAudit: No JUnit XML found under '$where' after running '$taskName'. " +
                        "Ensure JUnit XML reports are enabled and the report location is correctly wired."
            )
        }

        val findings = TestReportParser.parse(xmlFiles, mergedExceptions)

        val status = if (findings.size <= tol) Status.PASS else Status.FAIL
        val result = CheckpointResult(
            module = project.name,
            name = "tests",
            findings = findings,
            tolerance = tol,
            findingCount = findings.size,
            status = status
        )

        onResult?.invoke(result)
    }

    private fun containsAnyClassFile(files: FileCollection): Boolean {
        for (dir in files.files) {
            if (dir.exists() && dir.isDirectory) {
                if (dir.walkTopDown().any { it.isFile && it.extension == "class" }) {
                    return true
                }
            }
        }
        return false
    }
}

/**
 * Pure parser that reads JUnit XML files and returns Findings.
 * - XPath used to target failing/erroring testcases precisely.
 * - Whitelist applies to testcase @classname via PathMatcher ("glob:") with dots as separators.
 */
object TestReportParser {

    private val dbf = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isNamespaceAware = false
        isValidating = false
    }

    private val xpf = XPathFactory.newInstance()

    fun parse(xmlFiles: List<File>, exceptionPatterns: List<String>): List<Finding> {
        if (xmlFiles.isEmpty()) return emptyList()
        val matchers = buildMatchers(exceptionPatterns)
        val out = mutableListOf<Finding>()
        xmlFiles.forEach { file ->
            try {
                val doc = dbf.newDocumentBuilder().parse(file)
                val xp = xpf.newXPath()

                // Select all testcases that have failure or error children
                val nodes = xp.evaluate("//testcase[failure or error]", doc, XPathConstants.NODESET) as org.w3c.dom.NodeList
                for (i in 0 until nodes.length) {
                    val tc = nodes.item(i) as Element
                    val classname = tc.getAttribute("classname") ?: ""
                    if (classname.isNotBlank() && isWhitelisted(classname, matchers)) {
                        continue
                    }

                    val failure = (tc.getElementsByTagName("failure")?.item(0) as? Element)
                    val error = (tc.getElementsByTagName("error")?.item(0) as? Element)
                    val isFailure = failure != null
                    val culprit = failure ?: error
                    if (culprit == null) continue

                    val msgAttr = culprit.getAttribute("message") ?: ""
                    val body = culprit.textContent ?: ""

                    val (filePath, lineNum) = extractLocation(body, classname)

                    val findingType = if (isFailure) "TEST_FAILURE" else "TEST_ERROR"
                    val severity = "ERROR"

                    val lines = body.split('\n').map { it.trimEnd('\r') }

                    val message = if (msgAttr.isNotBlank()) msgAttr else firstNonEmptyLine(lines) ?: "Test failed"

                    out += Finding(
                        type = findingType,
                        filePath = filePath,
                        line = lineNum,
                        severity = severity,
                        message = message,
                        stacktrace = if (body.isBlank()) emptyList() else lines
                    )
                }
            } catch (e: Exception) {
                throw GradleException("Failed to parse JUnit XML report at ${file.path}: ${e.message}", e)
            }
        }
        return out
    }

    private fun firstNonEmptyLine(lines: List<String>): String? =
        lines.firstOrNull { it.isNotBlank() }

    private val stackRegex = Regex("""\(([^():\s]+\.(?:kt|java|groovy|scala)):(\d+)\)""")

    internal fun extractLocation(stackText: String, fallbackClassname: String): Pair<String?, Int?> {
        val m = stackRegex.find(stackText)
        return if (m != null) {
            m.groupValues[1] to m.groupValues[2].toInt()
        } else {
            // fall back to FQCN if we have nothing else
            if (fallbackClassname.isBlank()) null to null else fallbackClassname to null
        }
    }

    private fun buildMatchers(patterns: List<String>): List<java.nio.file.PathMatcher> =
        patterns.filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .map { pat ->
                val glob = "glob:" + pat.replace('.', '/')
                FileSystems.getDefault().getPathMatcher(glob)
            }

    private fun isWhitelisted(fqcn: String, matchers: List<java.nio.file.PathMatcher>): Boolean {
        if (matchers.isEmpty()) return false
        val asPath = Paths.get(fqcn.replace('.', '/'))
        return matchers.any { it.matches(asPath) }
    }
}
