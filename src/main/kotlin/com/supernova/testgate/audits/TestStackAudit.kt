// File: TestStackAudit.kt
package com.supernova.testgate.audits

import org.gradle.api.logging.Logger
import java.io.File
import kotlin.math.min

/**
 * 5) Test Stack Policy (JVM tests) — auditsStack
 *
 * Goal: Keep JVM tests modern and coroutine-correct.
 * Scope: src/test/kotlin/**/*.kt
 *
 * Fails when (tolerance = 0):
 *  - Banned imports: org.junit.Test, and any under:
 *      androidx.test.*, org.robolectric.*, androidx.test.espresso.*, androidx.compose.ui.test.*
 *  - Banned annotations: @Ignore (also @org.junit.Ignore)
 *  - Coroutines misuse:
 *      - runBlocking(
 *      - Thread.sleep(
 *      - Any scheduler APIs without runTest(
 *        (advanceUntilIdle(, advanceTimeBy(, runCurrent(, TestCoroutineScheduler, StandardTestDispatcher,
 *         UnconfinedTestDispatcher, TestScope)
 *  - Missing Main dispatcher rule: file uses Dispatchers.Main or viewModelScope but not MainDispatcherRule
 *
 * Exceptions:
 *  - Path whitelist only (relative to moduleDir). If a file matches any whitelist glob, it's skipped.
 *
 * Processing flow:
 *  1) Parse "tool output" (the test sources)
 *  2) Generate findings
 *  3) Aggregate into AuditResult
 *  4) Invoke the callback passed to check()
 */
class TestStackAudit(
    private val module: String,
    private val moduleDir: File,
    private val whitelistPaths: List<String>,
    private val logger: Logger
) : Audit {

    private val auditName = "auditsStack"
    private val tolerancePercent = 0 // hardcoded as requested

    private val whitelistMatcher = WhitelistMatcher(whitelistPaths)

    // ---- Rules (constants) ----
    private val bannedImportExact = setOf("org.junit.Test")
    private val bannedImportPrefixes = listOf(
        "androidx.test.",
        "org.robolectric.",
        "androidx.test.espresso.",
        "androidx.compose.ui.test."
    )
    // Ban both JUnit4 @Ignore and all JUnit5 @Disabled* variants (incl. conditional ones)
    private val bannedAnnotationRegex = Regex("""@\s*(?:org\.junit\.[\w.]*\.)?(?:Ignore|Disabled\w*)\b""")

    private val runBlockingRegex = Regex("""\brunBlocking\s*(?:<[^>]*>)?\s*(?:\(|\{)""")
    private val threadSleepRegex = Regex("""\bThread\.sleep\s*\(""")
    private val schedulerTokens = listOf(
        "advanceUntilIdle(",
        "advanceTimeBy(",
        "runCurrent(",
        "TestCoroutineScheduler",
        "StandardTestDispatcher",
        "UnconfinedTestDispatcher",
        "TestScope"
    )
    private val runTestRegex = Regex("""\brunTest\s*(?:<[^>]*>)?\s*(?:\(|\{)""")
    private val usesMainRegex = Regex("""Dispatchers\.Main\b|viewModelScope\b""")
    private val mainRuleHint = "MainDispatcherRule" // simple, project-agnostic

    override fun check(callback: (AuditResult) -> Unit) {
        val findings = mutableListOf<Finding>()

        val testsRoot = moduleDir.resolve("src/test/kotlin")
        if (!testsRoot.exists() || !testsRoot.isDirectory) {
            // No JVM tests → pass with 0 findings (we don't treat absence as a configuration error here).
            logger.info("[$auditName] No JVM test directory found at: ${testsRoot.invariantPath()}")
            callback(
                AuditResult(
                    module = module,
                    name = auditName,
                    findings = emptyList(),
                    tolerance = tolerancePercent,
                    findingCount = 0,
                    status = Status.PASS
                )
            )
            return
        }

        val files = testsRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        for (file in files) {
            val relPath = file.relativeTo(moduleDir).invariantPath()
            if (whitelistMatcher.matchesPath(relPath)) {
                logger.debug("[$auditName] Skipping whitelisted file: $relPath")
                continue
            }

            val text = file.readText()
            val lines = text.lines()

            // 1) Banned imports
            val importLines = parseImportsWithLineNumbers(lines)
            // exact
            bannedImportExact.forEach { fq ->
                importLines[fq]?.let { ln ->
                    findings += finding(
                        type = "BANNED_IMPORT",
                        filePath = relPath,
                        line = ln,
                        message = "Banned import `$fq` in JVM tests; migrate to JUnit 5 (org.junit.jupiter.api.Test)."
                    )
                }
            }
            // prefixes
            importLines.keys.forEach { imp ->
                bannedImportPrefixes.firstOrNull { imp.startsWith(it) }?.let { prefix ->
                    findings += finding(
                        type = "BANNED_IMPORT",
                        filePath = relPath,
                        line = importLines[imp] ?: null,
                        message = "Banned test-stack import `$imp` (matches prefix `$prefix`)."
                    )
                }
            }

            // 2) Banned annotation: @Ignore / @org.junit.Ignore
            firstMatchLine(lines, bannedAnnotationRegex)?.let { ln ->
                findings += finding(
                    type = "BANNED_ANNOTATION",
                    filePath = relPath,
                    line = ln,
                    message = "Skipped/disabled annotations (`@Ignore`, `@Disabled*`) are banned; remove."
                )
            }

            // 3) Coroutines misuse
            firstMatchLine(lines, runBlockingRegex)?.let { ln ->
                findings += finding(
                    type = "COROUTINES_MISUSE",
                    filePath = relPath,
                    line = ln,
                    message = "Use `runTest { ... }` instead of `runBlocking` in unit tests."
                )
            }
            firstMatchLine(lines, threadSleepRegex)?.let { ln ->
                findings += finding(
                    type = "COROUTINES_MISUSE",
                    filePath = relPath,
                    line = ln,
                    message = "Avoid `Thread.sleep` in unit tests; use virtual time (`runTest` + scheduler) instead."
                )
            }

            // Scheduler APIs without runTest
            val schedulerFirstIdx = firstTokenIndex(lines, schedulerTokens)
            val hasRunTest = runTestRegex.containsMatchIn(text)
            if (schedulerFirstIdx != null && !hasRunTest) {
                val ln = schedulerFirstIdx + 1
                findings += finding(
                    type = "COROUTINES_MISUSE",
                    filePath = relPath,
                    line = ln,
                    message = "Scheduler APIs detected without `runTest { ... }`."
                )
            }

            // 4) Missing Main dispatcher rule
            val usesMainAt = firstMatchLine(lines, usesMainRegex)
            if (usesMainAt != null && !text.contains(mainRuleHint)) {
                findings += finding(
                    type = "MISSING_MAIN_DISPATCHER_RULE",
                    filePath = relPath,
                    line = usesMainAt,
                    message = "File uses `Dispatchers.Main`/`viewModelScope` but no `$mainRuleHint` found; add a Main dispatcher rule."
                )
            }
        }

        val status = if (findings.isEmpty()) Status.PASS else Status.FAIL
        val result = AuditResult(
            module = module,
            name = auditName,
            findings = findings,
            tolerance = tolerancePercent,
            findingCount = findings.size,
            status = status
        )
        callback(result)
    }

    // ---- tiny helpers (keep methods short & simple) ----

    private fun File.invariantPath(): String =
        this.path.replace('\\', '/')

    private fun parseImportsWithLineNumbers(lines: List<String>): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("import ")) {
                val raw = line.removePrefix("import ").trim()
                // drop alias if present: "foo.Bar as Baz"
                val fqcn = raw.substringBefore(" as ").trim()
                if (fqcn.isNotEmpty()) map[fqcn] = i + 1
            }
        }
        return map
    }

    private fun firstMatchLine(lines: List<String>, regex: Regex): Int? {
        for (i in lines.indices) {
            if (regex.containsMatchIn(lines[i])) return i + 1
        }
        return null
    }

    private fun firstTokenIndex(lines: List<String>, tokens: List<String>): Int? {
        var bestIdx: Int? = null
        for (t in tokens) {
            val idx = lines.indexOfFirst { it.contains(t) }
            if (idx >= 0) bestIdx = min(bestIdx ?: Int.MAX_VALUE, idx)
        }
        return bestIdx
    }

    private fun finding(
        type: String,
        filePath: String,
        line: Int?,
        message: String
    ): Finding = Finding(
        type = type,
        filePath = filePath,
        line = line,
        severity = "error",
        message = message
    )
}