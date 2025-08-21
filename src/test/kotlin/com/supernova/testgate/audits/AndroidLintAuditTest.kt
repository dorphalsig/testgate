package com.supernova.testgate.audits

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class AndroidLintAuditTest {

    @Test
    fun `counts Error and Fatal only, unfolds multiple locations, applies whitelist`() {
        val tmp = createTmp2()
        createSrcFiles(tmp, 10)
        val report = File(tmp, "lint.xml")
        val xml = lintXmlFormat6(
            entries = listOf(
                L6Issue(
                    id = "NewApi", severity = "Error", message = "m",
                    locations = listOf(
                        L6Loc(File(tmp, "src/test/java/com/example/A.java").absolutePath, 1),
                        L6Loc(File(tmp, "src/test/java/com/example/Skip.java").absolutePath, 2)
                    )
                ),
                L6Issue(
                    id = "SomeWarn", severity = "Warning", message = "w",
                    locations = listOf(L6Loc(File(tmp, "src/test/java/com/example/W.java").absolutePath, 3))
                ),
                L6Issue(
                    id = "Hard", severity = "Fatal", message = "f",
                    locations = listOf(L6Loc(File(tmp, "src/test/java/com/example/B.java").absolutePath, 4))
                )
            )
        )
        report.writeText(xml)

        var result: AuditResult? = null
        AndroidLintAudit(
            module = ":app",
            reportXml = report,
            moduleDir = tmp,
            tolerancePercent = 25,
            whitelistPatterns = listOf("**/Skip.java"),
            logger = Logging.getLogger("AndroidLintAuditTest")
        ).check { result = it }

        assertNotNull(result)
        // Expected 2 Error/Fatal locations excluding Skip.java => A.java + B.java
        assertEquals(2, result!!.findingCount)
        assertEquals(Status.PASS, result!!.status) // 2/10 = 20% ≤ 25% → PASS
    }

    @Test
    fun `tolerance boundary pass`() {
        val tmp = createTmp2()
        createSrcFiles(tmp, 10)
        val report = File(tmp, "lint.xml")
        val xml = lintXmlFormat6(
            entries = listOf(
                L6Issue("X", "Error", "m", listOf(L6Loc(File(tmp, "src/test/java/com/example/A.java").absolutePath, 1)))
            )
        )
        report.writeText(xml)

        var result: AuditResult? = null
        AndroidLintAudit(
            module = ":app",
            reportXml = report,
            moduleDir = tmp,
            tolerancePercent = 10,
            whitelistPatterns = emptyList(),
            logger = Logging.getLogger("AndroidLintAuditTest")
        ).check { result = it }

        assertNotNull(result)
        // 1/10 = 10% equals tol → PASS
        assertEquals(Status.PASS, result!!.status)
    }

    @Test
    fun `missing lint file throws`() {
        val tmp = createTmp2()
        val audit = AndroidLintAudit(
            module = ":m",
            reportXml = File(tmp, "absent.xml"),
            moduleDir = tmp,
            tolerancePercent = null,
            whitelistPatterns = emptyList(),
            logger = Logging.getLogger("AndroidLintAuditTest")
        )
        assertThrows(org.gradle.api.GradleException::class.java) { audit.check { } }
    }
}

// --- Minimal XML builder for Lint format="6" used in tests ---
private data class L6Issue(val id: String, val severity: String, val message: String, val locations: List<L6Loc>)
private data class L6Loc(val file: String, val line: Int)
private fun lintXmlFormat6(entries: List<L6Issue>): String = buildString {
    append(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <issues format="6" by="lint">
    """.trimIndent()
    )
    entries.forEach { e ->
        append("\n  <issue id=\"${e.id}\" severity=\"${e.severity}\" message=\"${e.message}\">")
        e.locations.forEach { l ->
            append("\n    <location file=\"${l.file}\" line=\"${l.line}\"/>")
        }
        append("\n  </issue>")
    }
    append("\n</issues>")
}

private fun createTmp2(): File = kotlin.io.path.createTempDirectory("audit-lint").toFile().apply { deleteOnExit() }
