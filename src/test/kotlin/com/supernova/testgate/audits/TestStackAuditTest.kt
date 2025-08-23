// File: src/test/kotlin/com/supernova/testgate/audits/TestStackAuditTest.kt
package com.supernova.testgate.audits

import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TestStackAuditTest {

    private val logger: Logger = Logging.getLogger(TestStackAuditTest::class.java)

    @Test
    fun `passes when no JVM test dir`(@TempDir tmp: File) {
        val moduleDir = File(tmp, "m").apply { mkdirs() }
        val audit = TestStackAudit(
            module = "m",
            moduleDir = moduleDir,
            whitelistPaths = emptyList(),
            logger = logger
        )
        lateinit var result: AuditResult
        audit.check { result = it }

        assertEquals(Status.PASS, result.status)
        assertEquals(0, result.findingCount)
    }

    @Test
    fun `banned import - junit4 Test`(@TempDir tmp: File) {
        val src = writeKt(
            tmp, "src/test/kotlin/p/J4.kt", """
            package p
            import org.junit.Test
            class J4
            """.trimIndent()
        )
        val result = runAudit(tmp)

        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.filePath?.endsWith("J4.kt") == true && it.type == "BANNED_IMPORT" })
    }

    @Test
    fun `banned import - androidx test prefix`(@TempDir tmp: File) {
        writeKt(
            tmp, "src/test/kotlin/p/AX.kt", """
            package p
            import androidx.test.core.app.ApplicationProvider
            class AX
            """.trimIndent()
        )
        val result = runAudit(tmp)

        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.type == "BANNED_IMPORT" })
    }

    @Test
    fun `banned annotation - Ignore`(@TempDir tmp: File) {
        writeKt(
            tmp, "src/test/kotlin/p/Ig.kt", """
            package p
            @Ignore
            class Ig
            """.trimIndent()
        )
        val result = runAudit(tmp)

        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.type == "BANNED_ANNOTATION" })
    }

    @Test
    fun `banned annotation - Disabled`(@TempDir tmp: File) {
        writeKt(
            tmp, "src/test/kotlin/p/Dis.kt", """
            package p
            @Disabled
            class Dis
            """.trimIndent()
        )
        val result = runAudit(tmp)

        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.type == "BANNED_ANNOTATION" })
    }

    @Test
    fun `banned annotation - DisabledOnOs`(@TempDir tmp: File) {
        writeKt(
            tmp, "src/test/kotlin/p/DisOnOs.kt", """
            package p
            import org.junit.jupiter.api.condition.DisabledOnOs
            import org.junit.jupiter.api.condition.OS
            class DOnOs { @DisabledOnOs(OS.LINUX) fun t() {} }
            """.trimIndent()
        )
        val result = runAudit(tmp)

        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.type == "BANNED_ANNOTATION" })
    }

    @Test
    fun `coroutines misuse - runBlocking`() = runWithTmp { tmp ->
        writeKt(
            tmp, "src/test/kotlin/p/RB.kt", """
            package p
            import kotlinx.coroutines.runBlocking
            fun f() = runBlocking { }
            """.trimIndent()
        )
        val result = runAudit(tmp)

        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.type == "COROUTINES_MISUSE" && it.message.contains("runTest") })
    }

    @Test
    fun `coroutines misuse - Thread sleep`() = runWithTmp { tmp ->
        writeKt(
            tmp, "src/test/kotlin/p/TS.kt", """
            package p
            fun g() { Thread.sleep(10) }
            """.trimIndent()
        )
        val result = runAudit(tmp)

        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.type == "COROUTINES_MISUSE" && it.message.contains("Thread.sleep") })
    }

    @Test
    fun `scheduler apis without runTest`() = runWithTmp { tmp ->
        writeKt(
            tmp, "src/test/kotlin/p/Sched.kt", """
            package p
            fun tick() { advanceUntilIdle() }
            """.trimIndent()
        )
        val result = runAudit(tmp)

        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.type == "COROUTINES_MISUSE" && it.message.contains("Scheduler APIs") })
    }

    @Test
    fun `scheduler apis with runTest passes`() = runWithTmp { tmp ->
        writeKt(
            tmp, "src/test/kotlin/p/SchedOk.kt", """
            package p
            fun ok() {
                runTest { advanceUntilIdle() }
            }
            """.trimIndent()
        )
        val result = runAudit(tmp)

        assertEquals(Status.PASS, result.status)
        assertEquals(0, result.findingCount)
    }

    @Test
    fun `missing main dispatcher rule`() = runWithTmp { tmp ->
        writeKt(
            tmp, "src/test/kotlin/p/MainMissing.kt", """
            package p
            import kotlinx.coroutines.Dispatchers
            fun h() = Dispatchers.Main
            """.trimIndent()
        )
        val result = runAudit(tmp)

        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.type == "MISSING_MAIN_DISPATCHER_RULE" })
    }

    @Test
    fun `mentions MainDispatcherRule passes`() = runWithTmp { tmp ->
        writeKt(
            tmp, "src/test/kotlin/p/MainOk.kt", """
            package p
            import kotlinx.coroutines.Dispatchers
            val rule = MainDispatcherRule()
            fun h() = Dispatchers.Main
            """.trimIndent()
        )
        val result = runAudit(tmp)

        assertEquals(Status.PASS, result.status)
        assertEquals(0, result.findingCount)
    }

    @Test
    fun `whitelist path skips offending file`() = runWithTmp { tmp ->
        writeKt(
            tmp, "src/test/kotlin/legacy/Old.kt", """
            package legacy
            import org.junit.Test
            class Old
            """.trimIndent()
        )
        val result = runAudit(tmp, whitelist = listOf("src/test/kotlin/**/legacy/**"))

        assertEquals(Status.PASS, result.status)
        assertEquals(0, result.findingCount)
    }

    // -------------- helpers --------------

    private fun runWithTmp(block: (File) -> Unit) {
        val tmp = createTempDir(prefix = "audit")
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }

    private fun runAudit(tmp: File, whitelist: List<String> = emptyList()): AuditResult {
        val moduleDir = File(tmp, "m").apply { mkdirs() }
        val audit = TestStackAudit(
            module = "m",
            moduleDir = moduleDir,
            whitelistPaths = whitelist,
            logger = logger
        )
        lateinit var result: AuditResult
        audit.check { result = it }
        return result
    }

    private fun writeKt(root: File, relPath: String, content: String): File {
        val f = File(File(root, "m"), relPath)
        f.parentFile.mkdirs()
        f.writeText(content)
        return f
    }
}
