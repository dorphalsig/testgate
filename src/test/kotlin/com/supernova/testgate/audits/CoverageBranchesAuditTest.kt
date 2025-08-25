// File: src/test/kotlin/com/supernova/testgate/audits/CoverageBranchesAuditTest.kt
package com.supernova.testgate.audits

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CoverageBranchesAuditTest {

    @TempDir
    lateinit var tmp: File

    private fun writeReport(xml: String, name: String = "jacoco.xml"): File {
        val f = File(tmp, name)
        f.writeText(xml.trimIndent())
        return f
    }

    private fun runAudit(
        xml: String,
        threshold: Int? = 70,
        whitelist: List<String> = emptyList()
    ): AuditResult {
        val report = writeReport(xml)
        val audit = CoverageBranchesAudit(
            module = "app",
            reportXml = report,
            moduleDir = tmp,
            thresholdPercent = threshold,
            whitelistPatterns = whitelist
        )
        var result: AuditResult? = null
        audit.check { res -> result = res }
        return requireNotNull(result)
    }

    @Test
    fun `pass when total branch coverage meets threshold`() {
        val xml = """
            <report name="Test">
              <package name="com/example">
                <class name="com/example/Good" sourcefilename="Good.kt">
                  <counter type="BRANCH" missed="1" covered="9"/>
                </class>
                <class name="com/example/AlsoGood" sourcefilename="AlsoGood.kt">
                  <counter type="BRANCH" missed="0" covered="10"/>
                </class>
              </package>
            </report>
        """
        val res = runAudit(xml, threshold = 70)
        assertEquals(Status.PASS, res.status)
        assertTrue(res.findings.isEmpty())
        // total = 1+0 missed = 1, covered = 9+10=19 => 95.0%
        assertEquals(95.0, res.findingCount.toDouble(), 0.01)
        assertEquals("Coverage (branches)", res.name)
        assertEquals("app", res.module)
        assertEquals(0, res.tolerance)
    }

    @Test
    fun `fail lists offenders and reports percent in findingCount`() {
        val xml = """
            <report name="Test">
              <package name="com/example">
                <class name="com/example/Good" sourcefilename="Good.kt">
                  <counter type="BRANCH" missed="1" covered="9"/>
                </class>
                <class name="com/example/Bad" sourcefilename="Bad.kt">
                  <counter type="BRANCH" missed="7" covered="3"/>
                </class>
              </package>
            </report>
        """
        val res = runAudit(xml, threshold = 80)
        assertEquals(Status.FAIL, res.status)
        // Offenders: Good = 90%, Bad = 30% -> only Bad under 80%
        assertEquals(1, res.findings.size)
        val f = res.findings.first()
        assertEquals("ClassBelowThreshold", f.type)
        assertTrue(f.message.contains("com.example.Bad"))
        assertTrue(f.message.contains("< 80%"))
        assertEquals("com/example/Bad.kt", f.filePath)
        // total = missed 8, covered 12 => 60%
        assertEquals(60.0, res.findingCount.toDouble(), 0.01)
    }

    @Test
    fun `whitelist removes offenders and from totals`() {
        val xml = """
            <report name="Test">
              <package name="com/example">
                <class name="com/example/Good" sourcefilename="Good.kt">
                  <!-- 90% branches -->
                  <counter type="BRANCH" missed="1" covered="9"/>
                </class>
                <class name="com/example/Bad" sourcefilename="Bad.kt">
                  <counter type="BRANCH" missed="9" covered="1"/>
                </class>
              </package>
            </report>
        """
        // Without whitelist -> FAIL at 70% (Good 90%, Bad 10% -> total 50%)
        val resFail = runAudit(xml, threshold = 70, whitelist = emptyList())
        assertEquals(Status.FAIL, resFail.status)
        assertEquals(1, resFail.findings.size) // only Bad is below 70%

        // Whitelist Bad -> totals consider only Good (90%) and pass at 70%
        val res = runAudit(xml, threshold = 70, whitelist = listOf("com.example.Bad"))
        assertEquals(Status.PASS, res.status)
        assertTrue(res.findings.isEmpty())
        assertEquals(90.0, res.findingCount.toDouble(), 0.01)
    }

    @Test
    fun `classes without branch counters are ignored for offenders and compute as 0 contribution`() {
        val xml = """
            <report name="Test">
              <package name="p">
                <class name="p/NoBranches" sourcefilename="NoBranches.kt">
                  <counter type="METHOD" missed="0" covered="10"/>
                </class>
                <class name="p/Branched" sourcefilename="Branched.kt">
                  <counter type="BRANCH" missed="1" covered="1"/>
                </class>
              </package>
            </report>
        """
        val res = runAudit(xml, threshold = 60)
        assertEquals(Status.FAIL, res.status)
        // Only Branched is considered an offender candidate; it is 50% so listed.
        assertEquals(1, res.findings.size)
        assertTrue(res.findings.first().message.contains("p.Branched"))
        // totals come only from Branched: 50%
        assertEquals(50.0, res.findingCount.toDouble(), 0.01)
    }

    @Test
    fun `missing report throws clear exception`() {
        val report = File(tmp, "missing.xml")
        val audit = CoverageBranchesAudit(
            module = "app",
            reportXml = report,
            moduleDir = tmp,
            thresholdPercent = 70,
            whitelistPatterns = emptyList()
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            audit.check { /* no-op */ }
        }
        assertTrue(ex.message!!.contains("JaCoCo report not found"))
    }
}
