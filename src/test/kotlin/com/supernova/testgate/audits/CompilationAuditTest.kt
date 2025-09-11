package com.supernova.testgate.audits

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompilationAuditTest {

    @Test
    fun append_ignored_when_not_capturing(@TempDir dir: File) {
        val file = File(dir, "src/main/kotlin/com/example/Ignored.kt").apply {
            parentFile.mkdirs(); writeText("class Ignored")
        }
        val audit = CompilationAudit(module = "app", moduleDir = dir)

        // Not capturing yet → nothing should be recorded
        audit.append("e: ${file.path}: (1, 1): Should be ignored\n")

        var called = false
        audit.check { r ->
            called = true
            assertEquals(Status.PASS, r.status)
            assertTrue(r.findings.isEmpty())
            assertEquals(0, r.findingCount)
        }
        assertTrue(called)
    }

    @Test
    fun parses_kotlin_gradle_format_with_context(@TempDir dir: File) {
        val file = File(dir, "src/main/kotlin/com/example/F.kt").apply {
            parentFile.mkdirs(); writeText("class F")
        }
        val log = buildString {
            append("e: ${file.path}: (12, 8): Unresolved reference: Foo\n")
            append("symbol:   class Foo\n")
            append("location: class com.example.F\n")
            append("    at com.example.F.bar(F.kt:12)\n")
            append("^\n")
        }

        val audit = CompilationAudit(module = "app", moduleDir = dir)
        audit.registerCapture()
        audit.append(log)
        audit.unregisterCapture()

        audit.check { r ->
            assertEquals(Status.FAIL, r.status)
            assertEquals(1, r.findingCount)
            val f = r.findings.single()
            assertEquals("COMPILER_ERROR", f.type)
            // audit normalizes to path relative to moduleDir
            assertEquals("src/main/kotlin/com/example/F.kt", f.filePath?.replace('\\', '/'))
            assertEquals(12, f.line)
            assertEquals("ERROR", f.severity)
            assertTrue(f.message.contains("Unresolved reference: Foo"))
            // symbol/location are appended to message per parser logic:
            assertTrue(f.message.contains("symbol:"))
            assertTrue(f.message.contains("location:"))
            // Indented lines and caret go to stacktrace:
            assertTrue(f.stacktrace.any { it.contains("com.example.F.bar") })
            assertTrue(f.stacktrace.any { it == "^" })
        }
    }

    @Test
    fun parses_kotlin_cli_format(@TempDir dir: File) {
        val file = File(dir, "src/main/kotlin/com/example/Cli.kt").apply {
            parentFile.mkdirs(); writeText("class Cli")
        }
        val log = "${file.path}:5:7: error: Something bad\n\tat x.y.Z\n"

        val audit = CompilationAudit(module = "cli", moduleDir = dir)
        audit.registerCapture()
        audit.append(log)
        audit.unregisterCapture()

        audit.check { r ->
            assertEquals(Status.FAIL, r.status)
            val f = r.findings.single()
            assertEquals("src/main/kotlin/com/example/Cli.kt", f.filePath?.replace('\\', '/'))
            assertEquals(5, f.line)
            assertTrue(f.message.contains("Something bad"))
            assertTrue(f.stacktrace.any { it.contains("at x.y.Z") })
        }
    }

    @Test
    fun parses_javac_format(@TempDir dir: File) {
        val file = File(dir, "src/main/java/com/example/J.java").apply {
            parentFile.mkdirs(); writeText("class J{}")
        }
        val log = buildString {
            append("${file.path}:12: error: cannot find symbol\n")
            append("symbol: class Nope\n")
        }

        val audit = CompilationAudit(module = "java", moduleDir = dir)
        audit.registerCapture()
        audit.append(log)
        audit.unregisterCapture()

        audit.check { r ->
            assertEquals(Status.FAIL, r.status)
            val f = r.findings.single()
            assertEquals("src/main/java/com/example/J.java", f.filePath?.replace('\\', '/'))
            assertEquals(12, f.line)
            assertEquals("ERROR", f.severity)
            assertTrue(f.message.contains("cannot find symbol"))
            // "symbol:" is appended to message:
            assertTrue(f.message.contains("symbol: class Nope"))
        }
    }

    @Test
    fun parses_ksp_and_kapt_messages(@TempDir dir: File) {
        val kspFile = File(dir, "src/main/kotlin/com/example/Anno.kt").apply {
            parentFile.mkdirs(); writeText("@Anno class Anno")
        }
        val log = buildString {
            append("[ksp] ${kspFile.path}:9:1: Generated file has issues\n")
            append("e: [ksp] Aggregate message from processor\n")
            append("e: [kapt] Another tool message\n")
        }

        val audit = CompilationAudit(module = "ksp", moduleDir = dir)
        audit.registerCapture()
        audit.append(log)
        audit.unregisterCapture()

        audit.check { r ->
            assertEquals(Status.FAIL, r.status)
            assertEquals(3, r.findingCount)
            assertTrue(r.findings.any { it.filePath == "src/main/kotlin/com/example/Anno.kt" && it.line == 9 })
            assertTrue(r.findings.any { it.message.contains("Aggregate message from processor") })
            assertTrue(r.findings.any { it.message.contains("Another tool message") })
        }
    }

    @Test
    fun handles_crlf_newlines(@TempDir dir: File) {
        val file = File(dir, "src/main/kotlin/com/example/CrLf.kt").apply {
            parentFile.mkdirs(); writeText("class CrLf")
        }
        val log = "e: ${file.path}: (2, 1): Bad\r\nsymbol:   class X\r\nlocation: class Y\r\n"

        val audit = CompilationAudit(module = "crlf", moduleDir = dir)
        audit.registerCapture()
        audit.append(log)
        audit.unregisterCapture()

        audit.check { r ->
            assertEquals(1, r.findingCount)
            val f = r.findings.single()
            assertEquals(2, f.line)
            assertTrue(f.message.contains("Bad"))
            assertTrue(f.message.contains("symbol:"))
            assertTrue(f.message.contains("location:"))
        }
    }

    @Test
    fun blank_line_flushes_pending_finding(@TempDir dir: File) {
        val a = File(dir, "src/main/java/com/example/A.java").apply {
            parentFile.mkdirs(); writeText("class A{}")
        }
        val b = File(dir, "src/main/java/com/example/B.java").apply {
            writeText("class B{}")
        }
        val log = buildString {
            append("${a.path}:1: error: first\n")
            append("symbol: class Nope\n")
            append("\n") // blank → flush
            append("${b.path}:2: error: second\n")
        }

        val audit = CompilationAudit(module = "flush", moduleDir = dir)
        audit.registerCapture()
        audit.append(log)
        audit.unregisterCapture()

        audit.check { r ->
            assertEquals(2, r.findingCount)
            val msgs = r.findings.map { it.message }.toSet()
            // first finding includes symbol: in message
            assertTrue(msgs.any { it.startsWith("first") })
            assertTrue(msgs.any { it.contains("second") })
        }
    }

    @Test
    fun concurrent_appends_are_safe(@TempDir dir: File) {
        val a = File(dir, "src/main/kotlin/com/example/A.kt").apply {
            parentFile.mkdirs(); writeText("class A")
        }
        val b = File(dir, "src/main/kotlin/com/example/B.kt").apply {
            writeText("class B")
        }

        val audit = CompilationAudit(module = "concurrency", moduleDir = dir)
        audit.registerCapture()

        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        pool.submit {
            start.await()
            repeat(100) { audit.append("e: ${a.path}: (1, 1): errA\n") }
            done.countDown()
        }
        pool.submit {
            start.await()
            repeat(100) { audit.append("${b.path}:2:8: error: errB\n") }
            done.countDown()
        }

        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        audit.unregisterCapture()

        audit.check { r ->
            assertEquals(Status.FAIL, r.status)
            assertEquals(200, r.findingCount)
            assertTrue(r.findings.any { it.message == "errA" })
            assertTrue(r.findings.any { it.message == "errB" })
        }
    }
}
