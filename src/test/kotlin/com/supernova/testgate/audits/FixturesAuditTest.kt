package com.supernova.testgate.audits

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FixturesAuditTest {

    @TempDir
    lateinit var tmp: File

    private fun moduleDir(name: String = "m"): File {
        val dir = File(tmp, name).apply { mkdirs() }
        return dir
    }

    private fun fixturesDir(mod: File): File {
        return File(mod, "src/test/resources").apply { mkdirs() }
    }

    private fun writeBytes(f: File, size: Int) {
        f.parentFile?.mkdirs()
        f.outputStream().use { out ->
            repeat(size) { out.write('a'.code) }
        }
        assertEquals(size.toLong(), f.length(), "size must match for ${f.name}")
    }

    private fun runAudit(
        mod: File,
        whitelist: List<String> = emptyList(),
        tolerance: Int? = 10,
        min: Int = 256,
        max: Int = 8192
    ): AuditResult {
        val audit = FixturesAudit(
            module = "app",
            moduleDir = mod,
            tolerancePercent = tolerance,
            minBytes = min,
            maxBytes = max,
            whitelistPatterns = whitelist,
            logger = null
        )
        var result: AuditResult? = null
        audit.check { ar -> result = ar }
        return result!!
    }

    @Test
    fun `no fixtures and not whitelisted = fail missing`() {
        val mod = moduleDir("a")
        val r = runAudit(mod)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.findings.any { it.type == "MissingFixture" && it.severity == "error" })
    }

    @Test
    fun `no fixtures and whitelisted = pass`() {
        val mod = moduleDir("b")
        val r = runAudit(mod, whitelist = listOf("/**"))
        assertEquals(Status.PASS, r.status)
        assertTrue(r.findings.isEmpty())
    }

    @Test
    fun `one small fixture within window = pass`() {
        val mod = moduleDir("c")
        val fixtures = fixturesDir(mod)
        writeBytes(File(fixtures, "ok.json"), 300)
        val r = runAudit(mod)
        assertEquals(Status.PASS, r.status)
        assertTrue(r.findings.isEmpty())
    }

    @Test
    fun `exactly max bytes is allowed`() {
        val mod = moduleDir("d")
        val fixtures = fixturesDir(mod)
        writeBytes(File(fixtures, "edge.json"), 8192)
        val r = runAudit(mod)
        assertEquals(Status.PASS, r.status)
        assertTrue(r.findings.none { it.type == "FixtureOversize" })
    }

    @Test
    fun `ten fixtures with one oversize = pass at 10 percent`() {
        val mod = moduleDir("e")
        val fx = fixturesDir(mod)
        // 9 ok files
        repeat(9) { idx -> writeBytes(File(fx, "ok$idx.json"), 300) }
        // 1 oversize
        writeBytes(File(fx, "big.json"), 9000)
        val r = runAudit(mod, tolerance = 10)
        assertEquals(Status.PASS, r.status)
        assertEquals(1, r.findings.count { it.type == "FixtureOversize" })
    }

    @Test
    fun `three fixtures with one oversize = fail over tolerance`() {
        val mod = moduleDir("f")
        val fx = fixturesDir(mod)
        writeBytes(File(fx, "ok1.json"), 300)
        writeBytes(File(fx, "ok2.json"), 300)
        writeBytes(File(fx, "big.json"), 10000)
        val r = runAudit(mod, tolerance = 10)
        assertEquals(Status.FAIL, r.status)
        assertEquals(1, r.findings.count { it.type == "FixtureOversize" })
    }

    @Test
    fun `tiny fixture triggers too small`() {
        val mod = moduleDir("g")
        val fx = fixturesDir(mod)
        writeBytes(File(fx, "tiny.json"), 2)
        val r = runAudit(mod)
        assertEquals(Status.FAIL, r.status) // 100% violations
        assertEquals(1, r.findings.count { it.type == "FixtureTooSmall" })
    }

    @Test
    fun `exactly min bytes is allowed`() {
        val mod = moduleDir("h")
        val fx = fixturesDir(mod)
        writeBytes(File(fx, "edgeMin.json"), 256)
        val r = runAudit(mod)
        assertEquals(Status.PASS, r.status)
        assertTrue(r.findings.none { it.type == "FixtureTooSmall" })
    }

    @Test
    fun `callback populates correct fields`() {
        val mod = moduleDir("i")
        val fx = fixturesDir(mod)
        writeBytes(File(fx, "ok.json"), 300)
        val r = runAudit(mod, tolerance = 10)
        assertEquals("app", r.module)
        assertEquals("auditsFixtures", r.name)
        assertEquals(10, r.tolerance)
        assertEquals(Status.PASS, r.status)
    }
}
