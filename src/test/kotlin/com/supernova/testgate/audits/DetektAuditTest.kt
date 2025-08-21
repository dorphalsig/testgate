package com.supernova.testgate.audits

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class DetektAuditTest {

    @Test
    fun `hard fail rules force FAIL but do not throw`() {
        val tmp = createTmp()
        createSrcFiles(tmp, 10)
        val report = File(tmp, "detekt.xml")
        val xml = detektCheckstyleXml(
            File(tmp, "src/test/java/com/example/A.java").absolutePath,
            listOf(
                CheckstyleEntry("error", 1, "[ForbiddenImport] 'java.util.Date'")
            )
        )
        report.writeText(xml)

        var result: AuditResult? = null
        DetektAudit(
            module = ":app",
            reportXml = report,
            moduleDir = tmp,
            tolerancePercent = 10,
            whitelistPatterns = emptyList(),
            hardFailRuleIdsProperty = null,
            logger = Logging.getLogger("DetektAuditTest")
        ).check { result = it }

        assertNotNull(result)
        assertEquals(Status.FAIL, result!!.status)
        assertEquals(1, result!!.findingCount)
    }

    @Test
    fun `soft tolerance applies when no hard issues`() {
        val tmp = createTmp()
        createSrcFiles(tmp, 20)
        val report = File(tmp, "detekt.xml")
        val xml = detektCheckstyleXml(
            File(tmp, "src/test/java/com/example/B.java").absolutePath,
            listOf(
                CheckstyleEntry("error", 2, "[SomeOtherRule] msg"),
                CheckstyleEntry("warning", 3, "[WarnRule] msg") // ignored
            )
        )
        report.writeText(xml)

        var result: AuditResult? = null
        DetektAudit(
            module = ":lib",
            reportXml = report,
            moduleDir = tmp,
            tolerancePercent = 5,
            whitelistPatterns = emptyList(),
            hardFailRuleIdsProperty = listOf("ForbiddenImport", "ForbiddenMethodCall", "RequireHarnessAnnotationOnTests"),
            logger = Logging.getLogger("DetektAuditTest")
        ).check { result = it }

        assertNotNull(result)
        // 1 issue over 20 files => 5% equals tol => PASS
        assertEquals(Status.PASS, result!!.status)
        assertEquals(1, result!!.findingCount)
    }

    @Test
    fun `whitelist excludes entire file for detekt`() {
        val tmp = createTmp()
        createSrcFiles(tmp, 5)
        val target = File(tmp, "src/test/java/com/example/SkipMe.java").absolutePath
        val report = File(tmp, "detekt.xml")
        val xml = detektCheckstyleXml(
            target,
            listOf(CheckstyleEntry("error", 5, "[SomeRule] msg"))
        )
        report.writeText(xml)

        var result: AuditResult? = null
        DetektAudit(
            module = ":m",
            reportXml = report,
            moduleDir = tmp,
            tolerancePercent = 0,
            whitelistPatterns = listOf("**/SkipMe.java"),
            hardFailRuleIdsProperty = null,
            logger = Logging.getLogger("DetektAuditTest")
        ).check { result = it }

        assertNotNull(result)
        assertEquals(0, result!!.findingCount)
        assertEquals(Status.PASS, result!!.status)
    }

    @Test
    fun `missing report throws gradle exception`() {
        val tmp = createTmp()
        val report = File(tmp, "missing.xml")
        val audit = DetektAudit(
            module = ":x",
            reportXml = report,
            moduleDir = tmp,
            tolerancePercent = 10,
            whitelistPatterns = emptyList(),
            hardFailRuleIdsProperty = null,
            logger = Logging.getLogger("DetektAuditTest")
        )
        val ex = assertThrows(org.gradle.api.GradleException::class.java) {
            audit.check { }
        }
        assertTrue(ex.message!!.contains("Detekt report not found"))
    }
}

private fun createTmp(): File = kotlin.io.path.createTempDirectory("audit").toFile().apply { deleteOnExit() }
