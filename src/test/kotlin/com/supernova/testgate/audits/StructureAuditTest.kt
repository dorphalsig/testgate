package com.supernova.testgate.audits

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class StructureAuditTest {

    @Test
    fun happyPath_passes() {
        val root = tempModule()
        write(root, "src/test/kotlin/FooTest.kt", "class FooTest")
        write(root, "build.gradle.kts", """
            plugins { kotlin("jvm") version "1.9.24" }
            dependencies {
                testImplementation(project(":testing-harness"))
            }
        """.trimIndent())

        val result = runAudit(root)
        assertEquals(Status.PASS, result.status)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun androidTest_allowed_directory_passes() {
        val root = tempModule()
        // Presence of androidTest dir is allowed now
        mkdir(root, "src/androidTest")
        write(root, "build.gradle", "dependencies {}")

        val result = runAudit(root)
        assertEquals(Status.PASS, result.status)
        assertTrue(result.findings.none { it.type == "BannedSourceSet" })
    }


    @Test
    fun androidTest_scope_disallowed_import_fails() {
        val root = tempModule()
        write(
            root,
            "src/androidTest/kotlin/ScopeTest.kt",
            """
        package com.supernova.app
        import com.supernova.data.db.ProgramDao
        import org.junit.Test
        class ScopeTest {
            @Test fun dummy() { }
        }
        """.trimIndent()
        )
        write(root, "build.gradle", "dependencies {}")

        val result = runAudit(root)
        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.type == "InstrumentedScopeBan" })
    }


    @Test
    fun bannedSourceSet_sharedTest_fails() {
        val root = tempModule()
        mkdir(root, "src/sharedTest")
        write(root, "build.gradle", "dependencies {}")

        val result = runAudit(root)
        assertEquals(Status.FAIL, result.status)
        assertEquals("BannedSourceSet", result.findings.first().type)
    }

    @Test
    fun misplaced_java_under_test_fails() {
        val root = tempModule()
        write(root, "src/test/java/FooTest.java", "class FooTest{}")
        write(root, "build.gradle", "dependencies {}")

        val result = runAudit(root)
        assertEquals(Status.FAIL, result.status)
        assertEquals("MisplacedTest", result.findings.first().type)
    }

    @Test
    fun misplaced_kt_outside_kotlin_folder_fails() {
        val root = tempModule()
        write(root, "src/test/Foo.kt", "class Foo{}")
        write(root, "build.gradle", "dependencies {}")

        val result = runAudit(root)
        assertEquals(Status.FAIL, result.status)
        assertEquals("MisplacedTest", result.findings.first().type)
    }

    @Test
    fun missing_harness_when_tests_present_fails() {
        val root = tempModule()
        write(root, "src/test/kotlin/FooTest.kt", "class FooTest")
        write(root, "build.gradle.kts", """
            plugins { kotlin("jvm") version "1.9.24" }
            dependencies {
                // testImplementation(project(":testing-harness"))  // intentionally missing
            }
        """.trimIndent())

        val result = runAudit(root)
        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.type == "MissingHarnessDependency" })
    }

    @Test
    fun resources_only_requires_harness() {
        val root = tempModule()
        write(root, "src/test/resources/logback-test.xml", "<configuration/>")
        write(root, "build.gradle", "dependencies {}")

        val result = runAudit(root)
        assertEquals(Status.FAIL, result.status)
        assertTrue(result.findings.any { it.type == "MissingHarnessDependency" })
    }

    // ---- helpers ----

    private fun runAudit(root: File): AuditResult {
        val audit = StructureAudit(
            module = "app",
            moduleDir = root,
            instrumentedAllowlist =  listOf(
                "com.supernova.data.db.fts.**",
                "com.supernova.security.securestorage.**"
            ),
            logger = null
        )
        var captured: AuditResult? = null
        audit.check { r -> captured = r }
        return requireNotNull(captured)
    }

    private fun tempModule(): File {
        val dir: Path = Files.createTempDirectory("module-")
        return dir.toFile().apply { deleteOnExit() }
    }

    private fun mkdir(root: File, rel: String) {
        val dir = File(root, rel)
        if (!dir.exists()) dir.mkdirs()
    }

    private fun write(root: File, rel: String, content: String) {
        val file = File(root, rel)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
