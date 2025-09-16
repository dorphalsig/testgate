package com.supernova.testgate.audits

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TestsAuditTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun `passes at boundary 10 percent`() {
        val dir = writeXml(
            """
            <testsuite name="s">
              ${repeatTestcases("com.ex.FooTest", pass = 9)}
              <testcase classname="com.ex.FooTest" name="f1"><failure message="boom">stack</failure></testcase>
              <testcase classname="com.ex.FooTest" name="sk"><skipped/></testcase>
            </testsuite>
            """.trimIndent()
        )
        val audit = TestsAudit(module = "app", resultsDir = dir, tolerancePercent = 10, whitelistPatterns = emptyList(), logger = null)
        var result: AuditResult? = null
        audit.check { result = it }

        val r = requireNotNull(result)
        assertEquals(Status.PASS, r.status)
        assertEquals(1, r.findingCount)
        assertEquals(1, r.findings.size)
        assertEquals("auditsTests", r.name)
    }

    @Test
    fun `fails when ratio over threshold`() {
        val dir = writeXml(
            """
            <testsuite name="s">
              ${repeatTestcases("com.ex.FooTest", pass = 8)}
              <testcase classname="com.ex.FooTest" name="f1"><failure message="x">st</failure></testcase>
              <testcase classname="com.ex.FooTest" name="f2"><error message="y">st</error></testcase>
            </testsuite>
            """.trimIndent()
        )
        val audit = TestsAudit("app", dir, tolerancePercent = 10, whitelistPatterns = emptyList(), logger = null)
        var result: AuditResult? = null
        audit.check { result = it }
        val r = requireNotNull(result)
        assertEquals(Status.FAIL, r.status)
        assertEquals(2, r.findingCount)
        assertEquals(2, r.findings.size)
    }

    @Test
    fun `skipped excluded from denominator`() {
        val dir = writeXml(
            """
            <testsuite>
              ${repeatTestcases("A", pass = 9)}
              <testcase classname="A" name="sk"><skipped/></testcase>
              <testcase classname="A" name="f"><failure message="m">st</failure></testcase>
            </testsuite>
            """.trimIndent()
        )
        val audit = TestsAudit("app", dir, tolerancePercent = 10, whitelistPatterns = emptyList(), logger = null)
        var result: AuditResult? = null
        audit.check { result = it }
        val r = requireNotNull(result)
        // executed = 10, failed = 1 => 10% => PASS
        assertEquals(Status.PASS, r.status)
        assertEquals(1, r.findingCount)
    }

    @Test
    fun `whitelist by FQCN excludes counts and findings`() {
        val dir = writeXml(
            """
            <testsuite>
              <testcase classname="com.example.FooTest" name="ok"/>
              <testcase classname="com.example.FooTest" name="bad"><failure message="m">st</failure></testcase>
            </testsuite>
            """.trimIndent()
        )
        val audit = TestsAudit("app", dir, tolerancePercent = 0, whitelistPatterns = listOf("com.example.FooTest"), logger = null)
        var result: AuditResult? = null
        audit.check { result = it }
        val r = requireNotNull(result)
        assertEquals(Status.PASS, r.status)
        assertTrue(r.findings.isEmpty())
    }

    @Test
    fun `whitelist by symbol excludes only that method`() {
        val dir = writeXml(
            """
            <testsuite>
              <testcase classname="pkg.FooTest" name="bad1"><failure message="m">st</failure></testcase>
              <testcase classname="pkg.FooTest" name="bad2"><failure message="m">st</failure></testcase>
            </testsuite>
            """.trimIndent()
        )
        val audit = TestsAudit("app", dir, tolerancePercent = 0, whitelistPatterns = listOf("pkg.FooTest#bad1"), logger = null)
        var result: AuditResult? = null
        audit.check { result = it }
        val r = requireNotNull(result)
        assertEquals(1, r.findings.size)
        assertEquals(Status.FAIL, r.status)
    }

    @Test
    fun `multiple xml files aggregate correctly`() {
        val d1 = writeXml(
            """
            <testsuite>
              <testcase classname="C1" name="p1"/>
              <testcase classname="C1" name="f1"><failure message="m">st</failure></testcase>
            </testsuite>
            """.trimIndent(),
            fileName = "a.xml"
        )
        writeXml(
            """
            <testsuite>
              <testcase classname="C2" name="p2"/>
              <testcase classname="C2" name="f2"><error message="e">st</error></testcase>
            </testsuite>
            """.trimIndent(),
            baseDir = d1,
            fileName = "b.xml"
        )
        val audit = TestsAudit("app", d1, tolerancePercent = 0, whitelistPatterns = emptyList(), logger = null)
        var result: AuditResult? = null
        audit.check { result = it }
        val r = requireNotNull(result)
        assertEquals(2, r.findings.size)
        assertEquals(Status.FAIL, r.status)
    }

    @Test
    fun `malformed xml throws`() {
        val dir = File(tmp, "bad").apply { mkdirs() }
        File(dir, "x.xml").writeText("<testsuite><testcase></testsuite") // broken end tag
        val audit = TestsAudit("app", dir, logger = null)
        val ex = assertThrows(IllegalStateException::class.java) {
            audit.check { }
        }
        assertTrue(ex.message!!.contains("Failed to parse"))
    }

    @Test
    fun `missing directory returns empty result`() {
        val dir = File(tmp, "missing")
        val audit = TestsAudit("app", dir, logger = null)
        var result: AuditResult? = null
        audit.check { result = it }

        val r = requireNotNull(result)
        assertEquals(Status.PASS, r.status)
        assertEquals(0, r.findingCount)
        assertTrue(r.findings.isEmpty())
    }

    @Test
    fun `missing xml references executed tasks`() {
        val dir = File(tmp, "empty").apply { mkdirs() }
        val audit = TestsAudit(
            module = "app",
            resultsDir = dir,
            logger = null,
            executedTaskNames = listOf(":app:testDebugUnitTest")
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            audit.check { }
        }
        assertTrue(ex.message!!.contains(":app:testDebugUnitTest"))
    }

    @Test
    fun `zero executed tests passes`() {
        val dir = writeXml(
            """
            <testsuite>
              <testcase classname="C" name="s"><skipped/></testcase>
            </testsuite>
            """.trimIndent()
        )
        val audit = TestsAudit("app", dir, tolerancePercent = 0, whitelistPatterns = emptyList(), logger = null)
        var result: AuditResult? = null
        audit.check { result = it }
        val r = requireNotNull(result)
        assertEquals(Status.PASS, r.status)
        assertTrue(r.findings.isEmpty())
    }

    @Test
    fun `finding includes message head and stack`() {
        val dir = writeXml(
            """
            <testsuite>
              <testcase classname="C" name="f">
                <failure message="boom">
                  line1
                  line2
                </failure>
              </testcase>
            </testsuite>
            """.trimIndent()
        )
        val audit = TestsAudit("app", dir, tolerancePercent = 0, whitelistPatterns = emptyList(), logger = null)
        var result: AuditResult? = null
        audit.check { result = it }
        val f = requireNotNull(result).findings.single()
        assertTrue(f.message.startsWith("C#f: boom"))
        assertTrue(f.stacktrace.any { it.contains("line1") })
    }

    // -------------- helpers --------------

    private fun writeXml(content: String, baseDir: File = File(tmp, "r").apply { mkdirs() }, fileName: String = "r.xml"): File {
        File(baseDir, fileName).writeText("""<?xml version="1.0" encoding="UTF-8"?>$content""")
        return baseDir
    }

    private fun repeatTestcases(classname: String, pass: Int): String =
        (1..pass).joinToString("\n") { """<testcase classname="$classname" name="p$it"/>""" }
}
