package com.supernova.testgate.audits

import org.gradle.api.logging.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Enforces: In src/test/ **, JUnit4 usage is forbidden.
 * In src/androidTest/ **, JUnit4 usage (AndroidJUnit4 runner) is allowed.
 * Tolerance = 0 (any finding fails).
 */

class JUnitStackAudit(
    private val module: String,
    private val moduleDir: File,
    private val logger: Logger
) : Audit {

    override fun check(callback: (AuditResult) -> Unit) {
        validateInputs()

        val testRoots = listOf(
            moduleDir.resolve("src/test/java"),
            moduleDir.resolve("src/test/kotlin")
        )

        val findings = mutableListOf<Finding>()
        testRoots.filter { it.exists() }.forEach { root ->
            Files.walk(root.toPath()).use { stream ->
                stream.filter(::isSourceFile).forEach { file ->
                    findings += scanFileForFindings(file)
                }
            }
        }

        val result = aggregate(findings)
        callback(result)
    }

// ---------- Helpers (≤30 LOC each) ----------

    private fun validateInputs() {
        if (!moduleDir.exists() || !moduleDir.isDirectory) {
            throw IllegalStateException(
                "JUnitStackAudit: moduleDir '${moduleDir.absolutePath}' is missing or not a directory."
            )
        }
    }

    private fun isSourceFile(p: Path): Boolean {
        val name = p.fileName?.toString()?.lowercase() ?: return false
        return name.endsWith(".kt") || name.endsWith(".java")
    }

    private fun scanFileForFindings(p: Path): List<Finding> {
        val file = p.toFile()
        val lines = Files.readAllLines(
            p,
            Charsets.UTF_8
        ) // Using Files.readAllLines to avoid buffering complexity.

        val matches = mutableListOf<Finding>()

        lines.forEachIndexed { idx, raw ->
            val lineNo = idx + 1
            val line = raw.trim()

// Exact imports
            when {
                IMPORT_JUNIT_TEST.matches(line) ->
                    matches += finding(file, lineNo, "Forbidden import: org.junit.Test")

                IMPORT_RUNWITH.matches(line) ->
                    matches += finding(file, lineNo, "Forbidden import: org.junit.runner.RunWith")

                IMPORT_ANDROIDX_ANDROIDJUNIT4.matches(line) ->
                    matches += finding(file, lineNo, "Forbidden import: androidx.test.ext.junit.runners.AndroidJUnit4")

                IMPORT_SUPPORT_ANDROIDJUNIT4.matches(line) ->
                    matches += finding(file, lineNo, "Forbidden import: android.support.test.runner.AndroidJUnit4")
            }

// Annotations / FQCN appearances
            when {
                AT_RUNWITH.matches(line) ->
                    matches += finding(file, lineNo, "Forbidden annotation: @RunWith(...)")

                AT_FQCN_RUNWITH.matches(line) ->
                    matches += finding(file, lineNo, "Forbidden annotation: @org.junit.runner.RunWith(...)")

                ANDROIDJUNIT4_CLASS_REF.matches(line) ->
                    matches += finding(file, lineNo, "Forbidden runner reference: AndroidJUnit4(::class|.class)")

                AT_ANDROIDX_ANDROIDJUNIT4.matches(line) ->
                    matches += finding(
                        file,
                        lineNo,
                        "Forbidden annotation: @androidx.test.ext.junit.runners.AndroidJUnit4"
                    )

                AT_SUPPORT_ANDROIDJUNIT4.matches(line) ->
                    matches += finding(file, lineNo, "Forbidden annotation: @android.support.test.runner.AndroidJUnit4")
            }
        }

        return matches
    }

    private fun finding(file: File, line: Int, detail: String): Finding =
        Finding(
            type = "JUnitStackPolicy",
            filePath = file.canonicalPath,
            line = line,
            severity = "error",
            message = "Forbidden in src/test: $detail"
        )

    private fun aggregate(findings: List<Finding>): AuditResult {
        val status = if (findings.isEmpty()) Status.PASS else Status.FAIL
        if (status == Status.FAIL) {
            logger.warn("[TestGate] JUnitStackAudit: ${findings.size} forbidden usages found in src/test.")
        } else {
            logger.info("[TestGate] JUnitStackAudit: no forbidden usages found.")
        }
        return AuditResult(
            module = module,
            name = "JUnitStackPolicy",
            findings = findings,
            tolerance = 0,
            findingCount = findings.size,
            status = status
        )
    }

    companion object {
        // Imports
        private val IMPORT_JUNIT_TEST = Regex("^\\s*import\\s+org\\.junit\\.Test\\b")
        private val IMPORT_RUNWITH = Regex("^\\s*import\\s+org\\.junit\\.runner\\.RunWith\\b")
        private val IMPORT_ANDROIDX_ANDROIDJUNIT4 =
            Regex("^\\s*import\\s+androidx\\.test\\.ext\\.junit\\.runners\\.AndroidJUnit4\\b")
        private val IMPORT_SUPPORT_ANDROIDJUNIT4 =
            Regex("^\\s*import\\s+android\\.support\\.test\\.runner\\.AndroidJUnit4\\b")

        // Annotations / references
        private val AT_RUNWITH = Regex("@\\s*RunWith\\s*\\(")
        private val AT_FQCN_RUNWITH = Regex("@\\s*org\\.junit\\.runner\\.RunWith\\s*\\(")
        private val ANDROIDJUNIT4_CLASS_REF = Regex("AndroidJUnit4(::class|\\.class)\\b")
        private val AT_ANDROIDX_ANDROIDJUNIT4 =
            Regex("@\\s*androidx\\.test\\.ext\\.junit\\.runners\\.AndroidJUnit4\\b")
        private val AT_SUPPORT_ANDROIDJUNIT4 =
            Regex("@\\s*android\\.support\\.test\\.runner\\.AndroidJUnit4\\b")
    }
}
