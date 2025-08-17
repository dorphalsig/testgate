package com.supernova.testgate.audits

import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import javax.inject.Inject
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Hidden Gradle task that parses Detekt's native XML report and produces one finding per (ruleId × file).
 * - Only severities == "error" are counted.
 * - Whitelist is a list of normal file globs (project-relative) that will exclude matching files.
 * - Pass/Fail is decided by comparing finding count against [tolerance].
 *
 * Responsibility scope: capture errors from files only. No build failure handling here.
 */
abstract class DetektAuditTask @Inject constructor(objects: ObjectFactory) : DefaultTask(), Audit {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val reportFile: RegularFileProperty

    /**
     * Comma/whitespace separated globs read from project property `testgate.exceptions.detekt`.
     * Example: "** /generated/ **, ** /com/acme/internal/ **".
     */
    @get:Input
    abstract val whitelistGlobs: ListProperty<String>

    /**
     * From project property `testgate.tolerance.detekt` (default 0).
     */
    @get:Input
    abstract val tolerance: Property<Int>

    init {
        // Defaults: build/reports/detekt/detekt.xml, empty whitelist, tolerance=0
        reportFile.convention(project.layout.buildDirectory.file("reports/detekt/detekt.xml"))
        whitelistGlobs.convention(resolveWhitelistGlobs())
        tolerance.convention(resolveTolerance())
        // Hidden task: no group/description
    }

    @TaskAction
    fun runAudit() {
        // Fire through the Audit API so callers/collectors can hook the result if they need to.
        check { /* no-op by default; tests or aggregators can supply a callback */ }
    }

    override fun check(callback: (CheckpointResult) -> Unit) {
        val xml = reportFile.get().asFile
        if (!xml.exists()) {
            throw GradleException("Detekt report file not found at ${'$'}{xml.path}")
        }
        val results = computeFindings(xml)
        val tol = tolerance.getOrElse(0)
        val status = if (results.size <= tol) Status.PASS else Status.FAIL
        val checkpoint = CheckpointResult(
            module = project.name,
            name = "detekt",
            findings = results,
            tolerance = tol,
            findingCount = results.size,
            status = status
        )
        callback(checkpoint)
    }

    private fun computeFindings(xmlFile: File): List<Finding> {
        val doc = secureFactory().newDocumentBuilder().parse(xmlFile)
        doc.documentElement.normalize()

        val xPath = XPathFactory.newInstance().newXPath()
        // Select only <issue> nodes with severity == 'error' (case-insensitive)
        val expr =
            "//issue[translate(@severity,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='error']"
        val issueNodes = xPath.evaluate(expr, doc, XPathConstants.NODESET) as org.w3c.dom.NodeList

        // Compile whitelist globs into PathMatchers once
        val matchers = whitelistGlobs.getOrElse(emptyList())
            .mapNotNull { pattern ->
                val p = pattern.trim()
                if (p.isEmpty()) null else FileSystems.getDefault()
                    .getPathMatcher("glob:" + normalizeGlobSeparators(p))

            }

        val projectPath: Path = project.projectDir.toPath()
        val seen = mutableMapOf<Pair<String, String>, Finding>()

        for (i in 0 until issueNodes.length) {
            val issue = issueNodes.item(i) as org.w3c.dom.Element
            val rawRuleId = issue.getAttribute("id")
            val ruleId = if (!rawRuleId.isBlank()) rawRuleId else continue
            val loc = firstChildLocation(issue) ?: continue
            val abs = Paths.get(loc.file)
            val rel = safeRelativize(projectPath, abs)

            // Apply whitelist globs against project-relative path
            if (matchers.any { it.matches(rel) }) continue

            val key = ruleId to rel.toString()
            val prev = seen[key]
            val line = loc.line
            val minLine = listOfNotNull(prev?.line, line).minOrNull()
            if (prev == null) {
                seen[key] = Finding(
                    type = "detekt",
                    filePath = rel.toString(),
                    line = line,
                    severity = "error",
                    message = ruleId,
                    stacktrace = emptyList()
                )
            } else if (minLine != prev.line) {
                // replace with min line to keep a stable representative
                seen[key] = prev.copy(line = minLine)
            }
        }
        return seen.values.sortedWith(
            compareBy(
                { it.filePath ?: "" },
                { it.message },
                { it.line ?: Int.MAX_VALUE })
        )
    }

    private data class Location(val file: String, val line: Int?)

    private fun firstChildLocation(issue: org.w3c.dom.Element): Location? {
        val nodes = issue.getElementsByTagName("location")
        if (nodes.length == 0) return null
        val el = nodes.item(0) as org.w3c.dom.Element
        val file = el.getAttribute("file")
        val lineStr = el.getAttribute("line")
        val line = lineStr.toIntOrNull()
        return if (file.isNullOrBlank()) null else Location(file, line)
    }

    private fun safeRelativize(base: Path, other: Path): Path {
        return try {
            base.relativize(other.normalize())
        } catch (_: IllegalArgumentException) {
            // If not under project dir, just return the file name or absolute path if needed
            other.normalize()
        }
    }

    private fun normalizeGlobSeparators(glob: String): String {
        // Let users write globs with forward slashes; normalize to platform separators.
        // The java.nio glob implementation is platform-dependent for separators.
        return if (File.separatorChar == '/') glob else glob.replace('/', File.separatorChar)
    }

    private fun secureFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {
            }
            try {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            } catch (_: Exception) {
            }
            try {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveWhitelistGlobs(): ListProperty<String> {
        val prop = (project.findProperty("testgate.exceptions.detekt") as? String)?.trim().orEmpty()
        val items = prop
            .split(',', '\n', '\r', '\t', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return project.objects.listProperty(String::class.java).convention(items)
    }

    private fun resolveTolerance(): Property<Int> {
        val tol = (project.findProperty("testgate.tolerance.detekt") as? String)?.toIntOrNull()
            ?: (project.findProperty("testgate.tolerance.detekt") as? Number)?.toInt()
            ?: 0
        return project.objects.property(Int::class.java).convention(tol)
    }
}
