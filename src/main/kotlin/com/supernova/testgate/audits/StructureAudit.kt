package com.supernova.testgate.audits

import org.gradle.api.logging.Logger
import java.io.File
import java.nio.file.Files

/**
 * auditsStructure — Ensures:
 *  - Banned source sets do not exist: src/androidTest/ **, src/sharedTest/ **
 *  - Tests only under src/test/kotlin/ **
 *  - If tests (any under src/test/ **) or test resources exist, the module declares
 *    testImplementation(project(":testing-harness"))
 *
 * Tolerance: fixed to 0 (any finding fails).
 * No whitelist support.
 */

class StructureAudit(
    private val module: String,
    private val moduleDir: File,
    private val logger: Logger? = null
) : Audit {

    override fun check(callback: (AuditResult) -> Unit) {
        require(moduleDir.isDirectory) {
            "Module directory does not exist or is not a directory: ${moduleDir.absolutePath}"
        }

        val findings = mutableListOf<Finding>()

// 1) Parse inputs (FS & build file)
        val banned = findBannedSourceSets()
        findings += banned

        val misplaced = findMisplacedTests()
        findings += misplaced

        val hasTestsOrResources = detectTestsOrResources()
        val buildFile = readBuildFileOrThrow()

// 2) Generate findings (missing harness dep)
        if (hasTestsOrResources && !hasHarnessDependency(buildFile)) {
            findings += finding(
                type = "MissingHarnessDependency",
                filePath = rel(buildFile),
                line = 1,
                message = "Module has tests/resources but is missing testImplementation(project(\":testing-harness\"))."
            )
        }

// 3) Aggregate
        val tolerance = 0
        val status = if (findings.size > tolerance) Status.FAIL else Status.PASS
        val result = AuditResult(
            module = module,
            name = "auditsStructure",
            findings = findings,
            tolerance = tolerance,
            findingCount = findings.size,
            status = status
        )

        logger?.info("[auditsStructure] module=$module findings=${findings.size} status=$status")

// 4) Callback
        callback(result)
    }

// ---- Helpers ----

    private fun findBannedSourceSets(): List<Finding> {
        val bannedDirs = listOf("src/androidTest", "src/sharedTest")
        return bannedDirs.mapNotNull { relPath ->
            val dir = moduleDir.resolve(relPath)
            if (dir.exists()) {
                finding(
                    type = "BannedSourceSet",
                    filePath = rel(dir),
                    line = null,
                    message = "Banned source set found: ${rel(dir)}"
                )
            } else null
        }
    }

    private fun findMisplacedTests(): List<Finding> {
        val base = moduleDir.resolve("src/test")
        if (!base.exists()) return emptyList()

        val results = mutableListOf<Finding>()
        Files.walk(base.toPath()).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { p ->
                val path = rel(p.toFile())
                val lower = path.lowercase()

                val isKt = lower.endsWith(".kt")
                val isJava = lower.endsWith(".java")

// Only kt/java are considered; anything under test/java is misplaced,
// and any .kt that isn't under test/kotlin/** is misplaced.
                val underKotlin = lower.startsWith("src/test/kotlin/")
                val underTest = lower.startsWith("src/test/")

                val misplaced =
                    (isJava && underTest) ||
                            (isKt && (!underKotlin))

                if (misplaced) {
                    results += finding(
                        type = "MisplacedTest",
                        filePath = path,
                        line = 1,
                        message = "Tests must live under src/test/kotlin: $path"
                    )
                }
            }
        }
        return results
    }

    private fun detectTestsOrResources(): Boolean {
        val testRoot = moduleDir.resolve("src/test")
        if (!testRoot.exists()) return false

        var has = false
        Files.walk(testRoot.toPath()).use { stream ->
            stream.forEach { p ->
                if (has) return@forEach
                if (Files.isRegularFile(p)) {
                    val rp = rel(p.toFile()).lowercase()
                    val isKt = rp.endsWith(".kt")
                    val isResource = rp.startsWith("src/test/resources/")
                    val isAnyTest = rp.startsWith("src/test/")
                    if ((isKt && isAnyTest) || isResource) {
                        has = true
                    }
                }
            }
        }
        return has
    }

    private fun readBuildFileOrThrow(): File {
        val kts = moduleDir.resolve("build.gradle.kts")
        val groovy = moduleDir.resolve("build.gradle")
        return when {
            kts.exists() -> kts
            groovy.exists() -> groovy
            else -> throw IllegalStateException("No Gradle build file found in ${moduleDir.absolutePath}")
        }
    }

    private fun hasHarnessDependency(buildFile: File): Boolean {
        val raw = buildFile.readText()

// Strip /* */ block comments
        val noBlocks = raw.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
// Strip // line comments
        val body = noBlocks.lines().joinToString("\n") { line ->
            val cut = line.indexOf("//")
            if (cut >= 0) line.substring(0, cut) else line
        }

// Kotlin/Groovy with parentheses: testImplementation(project(":testing-harness"))
        val reParens = Regex(
            """testImplementation\s*\(\s*project\s*\(\s*['"]:testing-harness['"]\s*\)\s*\)""",
            RegexOption.MULTILINE
        )

// Groovy without extra parentheses: testImplementation project(':testing-harness')
        val reGroovyNoParens = Regex(
            """testImplementation\s+project\s*\(\s*['"]:testing-harness['"]\s*\)""",
            RegexOption.MULTILINE
        )

        return reParens.containsMatchIn(body) || reGroovyNoParens.containsMatchIn(body)
    }

    private fun finding(
        type: String,
        filePath: String?,
        line: Int?,
        message: String
    ): Finding = Finding(
        type = type,
        filePath = filePath,
        line = line,
        severity = "error",
        message = message
    )

    private fun rel(file: File): String =
        moduleDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
}
