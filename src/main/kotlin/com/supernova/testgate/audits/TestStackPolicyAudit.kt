package com.supernova.testgate.audits

import org.gradle.api.logging.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * TestStackPolicyAudit (smol): JVM tests coroutine correctness.
 *
 * Scope: src/test/kotlin/**/*.kt only.
 * Fails when:
 *  1) Scheduler APIs are used without runTest present in the same file.
 *  2) Main dispatcher (or ViewModel scope) is used without a configured "Main dispatcher rule" present.
 *
 * Config (via Gradle properties):
 *  - testgate.stack.allowlist.files = comma-separated glob patterns (optional)
 *  - testgate.stack.mainDispatcherRules = comma-separated FQCNs considered valid rules
 *
 * Tolerance = 0. Findings -> FAIL. True errors -> throw.
 */
class TestStackPolicyAudit(
    private val module: String,
    private val moduleDir: File,
    private val logger: Logger,
    private val allowlistFiles: List<String> = emptyList(),
    private val mainDispatcherRuleFqcns: List<String> = emptyList()
) : Audit {

    override fun check(callback: (AuditResult) -> Unit) {
        validateInputs()

        val roots = listOf(
            moduleDir.resolve("src/test/kotlin")
        ).filter { it.exists() }

        val allowMatcher = WhitelistMatcher(allowlistFiles)
        val findings = mutableListOf<Finding>()

        roots.forEach { root ->
            Files.walk(root.toPath()).use { stream ->
                stream.filter { isKotlin(it) }.forEach { file ->
                    val relPath = moduleDir.toPath().relativize(file).toString().replace('\\', '/')
                    if (allowMatcher.matchesPath(relPath)) return@forEach
                    findings += analyzeFile(file.toFile())
                }
            }
        }

        val result = aggregate(findings)
        callback(result)
    }

    // ----- helpers -----

    private fun validateInputs() {
        if (!moduleDir.exists() || !moduleDir.isDirectory) {
            throw IllegalStateException(
                "TestStackPolicyAudit: moduleDir '${moduleDir.absolutePath}' is missing or not a directory."
            )
        }
    }

    private fun isKotlin(p: Path): Boolean =
        p.fileName?.toString()?.endsWith(".kt", ignoreCase = true) == true

    private fun analyzeFile(file: File): List<Finding> {
        val text = file.readText(Charsets.UTF_8)
        val hasRunTest = RUN_TEST_REGEX.containsMatchIn(text)

        val localFindings = mutableListOf<Finding>()

        // 1) Scheduler APIs without runTest
        if (!hasRunTest && SCHEDULER_REGEX.containsMatchIn(text)) {
            localFindings += finding(
                file,
                detail = "Scheduler APIs used without runTest. Prefer wrapping test code in runTest { … }"
            )
        }

        // 2) Main dispatcher usage without a rule present
        if (MAIN_DISPATCHER_TRIGGER_REGEX.containsMatchIn(text)) {
            val hasRule = hasAnyRuleReference(text, mainDispatcherRuleFqcns)
            if (!hasRule) {
                val rules = if (mainDispatcherRuleFqcns.isEmpty()) "<no rules configured>"
                else mainDispatcherRuleFqcns.joinToString()
                localFindings += finding(
                    file,
                    detail = "Main dispatcher/viewModelScope used without a Main dispatcher rule. Add one of: $rules"
                )
            }
        }

        return localFindings
    }

    private fun hasAnyRuleReference(text: String, rules: List<String>): Boolean {
        if (rules.isEmpty()) return false
        return rules.any { fqcn ->
            val simple = fqcn.substringAfterLast('.')
            text.contains(fqcn) || SIMPLE_IDENTIFIER_REGEX(simple).containsMatchIn(text)
        }
    }

    private fun finding(file: File, detail: String): Finding =
        Finding(
            type = "TestStackPolicy",
            filePath = file.canonicalPath,
            line = null,
            severity = "error",
            message = detail
        )

    private fun aggregate(findings: List<Finding>): AuditResult {
        val status = if (findings.isEmpty()) Status.PASS else Status.FAIL
        if (status == Status.FAIL) {
            logger.warn("[TestGate] TestStackPolicyAudit: ${findings.size} issue(s) found in JVM tests.")
        } else {
            logger.info("[TestGate] TestStackPolicyAudit: no issues found.")
        }
        return AuditResult(
            module = module,
            name = "TestStackPolicy",
            findings = findings,
            tolerance = 0,
            findingCount = findings.size,
            status = status
        )
    }

    companion object {
        // Matches e.g. runTest { ... } or runTest(…)
        private val RUN_TEST_REGEX = Regex("""\brunTest\s*\(""")
        // Common scheduler/test dispatcher APIs
        private val SCHEDULER_REGEX = Regex(
            listOf(
                "\\bTestCoroutineDispatcher\\b",
                "\\bTestCoroutineScope\\b",
                "\\bTestCoroutineScheduler\\b",
                "\\bUnconfinedTestDispatcher\\b",
                "\\bStandardTestDispatcher\\b"
            ).joinToString("|")
        )
        // Triggers for "main dispatcher rule required"
        private val MAIN_DISPATCHER_TRIGGER_REGEX = Regex(
            listOf(
                "\\bDispatchers\\.Main\\b",
                "\\bviewModelScope\\b",
                // imports indicating ViewModel under test
                "\\bimport\\s+androidx\\.lifecycle\\.ViewModel\\b",
                "\\bimport\\s+androidx\\.lifecycle\\.viewModelScope\\b"
            ).joinToString("|")
        )

        private fun SIMPLE_IDENTIFIER_REGEX(id: String): Regex =
            Regex("""\b$id\b""")
    }
}
