package com.supernova.testgate.audits

import org.gradle.api.logging.Logger
import java.io.File
import java.nio.file.Files

/**
 * auditsStructure — v2 (with AndroidTest scope allow-list)
 *
 * Ensures:
 *  - BANNED: src/sharedTest/ ** (still banned)
 *  - JVM tests only under src/test/kotlin/ **
 *  - If tests/resources exist under src/test/ ** then build declares:
 *        testImplementation(project(":testing-harness"))
 *  - AND: Instrumented tests under src/androidTest/ ** may only reference
 *        allow-listed packages/FQCNs (scope-ban) with a configurable tolerance%.
 *
 * Tolerance for structure: fixed to 0 (any structural finding fails).
 * Tolerance for instrumented scope: percentage of offending androidTest FILES.
 *
 * Configuration is passed via constructor; wire from Gradle properties if desired.
 */
class StructureAudit(
    private val module: String,
    private val moduleDir: File,
    private val logger: Logger? = null,
    // ---- AndroidTest scope configuration ----
    private val instrumentedAllowlist: List<String> = emptyList(),
    private val instrumentedRootPackage: String = "com.supernova.",
    private val instrumentedTolerancePercent: Int = 0
) : Audit {

    override fun check(callback: (AuditResult) -> Unit) {
        require(moduleDir.isDirectory) {
            "Module directory does not exist or is not a directory: ${moduleDir.absolutePath}"
        }

        // 1) Parse inputs
        val findings = mutableListOf<Finding>()

        // 1a) Structure-only checks (unchanged policy)
        findings += findBannedSharedTest()
        findings += findMisplacedJvmTests()
        val hasTestsOrResources = detectJvmTestsOrResources()
        val buildFile = readBuildFileOrThrow()
        if (hasTestsOrResources && !hasHarnessDependency(buildFile)) {
            findings += finding(
                type = "MissingHarnessDependency",
                filePath = rel(buildFile),
                line = 1,
                message = "Module has tests/resources but is missing testImplementation(project(\":testing-harness\"))."
            )
        }

        // 1b) AndroidTest scope parse (collect files + headers)
        val androidTestFiles = collectAndroidTestFiles()
        val scopeFindings = validateAndroidTestScope(androidTestFiles)
        findings += scopeFindings

        // 2) Aggregate
        val structuralFindings = findings.filterNot { it.type == "InstrumentedScopeBan" }

        val offendingAndroidFiles = scopeFindings.mapNotNull { it.filePath }.toSet().size
        val androidFilesTotal = androidTestFiles.size

        val ratio = if (androidFilesTotal == 0) 0.0
        else offendingAndroidFiles * 100.0 / androidFilesTotal.toDouble()

        val structureFails = structuralFindings.isNotEmpty()
        val instrumentedFails = ratio > instrumentedTolerancePercent

        val status = if (structureFails || instrumentedFails) Status.FAIL else Status.PASS

        val result = AuditResult(
            module = module,
            name = "auditsStructure",
            findings = findings,
            tolerance = instrumentedTolerancePercent, // tolerance applies only to instrumented scope
            findingCount = findings.size,
            status = status
        )

        logger?.info(
            "[auditsStructure] module=$module structuralFindings=${structuralFindings.size} " +
                "androidFiles=$androidFilesTotal offendingFiles=$offendingAndroidFiles " +
                "tolerance=$instrumentedTolerancePercent ratio=${"%.2f".format(ratio)} status=$status"
        )

        // 3) Callback
        callback(result)
    }

    // ---- Structure helpers ----

    private fun findBannedSharedTest(): List<Finding> {
        val dir = moduleDir.resolve("src/sharedTest")
        if (!dir.exists()) return emptyList()
        return listOf(
            finding(
                type = "BannedSourceSet",
                filePath = rel(dir),
                line = null,
                message = "Banned source set found: ${rel(dir)}"
            )
        )
    }

    private fun findMisplacedJvmTests(): List<Finding> {
        val base = moduleDir.resolve("src/test")
        if (!base.exists()) return emptyList()

        val results = mutableListOf<Finding>()
        Files.walk(base.toPath()).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { p ->
                val path = rel(p.toFile())
                val lower = path.lowercase()

                val isKt = lower.endsWith(".kt")
                val isJava = lower.endsWith(".java")

                val underKotlin = lower.startsWith("src/test/kotlin/")
                val underTest = lower.startsWith("src/test/")

                val misplaced = (isJava && underTest) || (isKt && (!underKotlin))
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

    private fun detectJvmTestsOrResources(): Boolean {
        val testRoot = moduleDir.resolve("src/test")
        if (!testRoot.exists()) return false

        var has = false
        Files.walk(testRoot.toPath()).use { stream ->
            stream.forEach { p ->
                if (has) return@forEach
                if (!Files.isRegularFile(p)) return@forEach
                val rp = rel(p.toFile()).lowercase()
                val isKt = rp.endsWith(".kt")
                val isResource = rp.startsWith("src/test/resources/")
                val isAnyTest = rp.startsWith("src/test/")
                if ((isKt && isAnyTest) || isResource) has = true
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

    // ---- AndroidTest scope helpers ----

    private fun collectAndroidTestFiles(): List<File> {
        val base = moduleDir.resolve("src/androidTest")
        if (!base.exists()) return emptyList()
        val out = mutableListOf<File>()
        Files.walk(base.toPath()).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { p ->
                val name = p.fileName.toString().lowercase()
                if (name.endsWith(".kt") || name.endsWith(".java")) {
                    out += p.toFile()
                }
            }
        }
        return out
    }

    /**
     * Validate that each androidTest file only references allow-listed targets.
     * We flag imports that look like app code (start with instrumentedRootPackage)
     * and are NOT matched by the allow-list.
     */
    private fun validateAndroidTestScope(files: List<File>): List<Finding> {
        if (files.isEmpty()) return emptyList()

        val matcher = WhitelistMatcher(instrumentedAllowlist)
        val results = mutableListOf<Finding>()

        files.forEach { file ->
            val header = readHeader(file)
            if (header.imports.isEmpty()) return@forEach

            // map import -> line number for better messages
            val importLineMap = buildImportLineMap(file)

            header.imports
                .filter { it.startsWith(instrumentedRootPackage) }
                .forEach { imp ->
                    val allowed = matcher.matchesFqcnOrSymbol(imp)
                    if (!allowed) {
                        results += finding(
                            type = "InstrumentedScopeBan",
                            filePath = rel(file),
                            line = importLineMap[imp],
                            message = buildDisallowedMessage(imp)
                        )
                    }
                }
        }
        return results
    }

    private fun buildImportLineMap(file: File): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        val reImport = Regex("""^\s*import\s+([A-Za-z_][A-Za-z0-9_\.]*(?:\.\*)?)""")
        file.readLines().forEachIndexed { i, raw ->
            val m = reImport.find(raw)
            if (m != null) map[m.groupValues[1]] = i + 1
        }
        return map
    }

    private fun buildDisallowedMessage(importFqcn: String): String {
        val allowed = if (instrumentedAllowlist.isEmpty())
            "(no allowed packages configured)"
        else instrumentedAllowlist.joinToString(", ")
        return "Instrumented test references disallowed target: $importFqcn. Allowed: $allowed."
    }

    // ---- Model helpers ----

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

    private fun rel(file: File): String = moduleRelativePath(moduleDir, file)
}
