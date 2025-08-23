package com.supernova.testgate.audits

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class TestStackPolicyAuditTest {

    private val logger = Logging.getLogger(TestStackPolicyAuditTest::class.java)

    // ---------- helpers ----------

    private fun tempModule(): File = createTempDir(prefix = "module").apply {
        // Only create folders as needed by tests
    }

    private fun write(root: File, relPath: String, content: String) {
        val f = File(root, relPath)
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    private fun runAudit(
        moduleDir: File,
        allowFiles: List<String> = emptyList(),
        rules: List<String> = emptyList()
    ): AuditResult {
        var result: AuditResult? = null
        TestStackPolicyAudit(
            module = "m",
            moduleDir = moduleDir,
            logger = logger,
            allowlistFiles = allowFiles,
            mainDispatcherRuleFqcns = rules
        ).check { r -> result = r }
        return result!!
    }

    // ---------- tests ----------

    @Test
    fun `PASS when there is no src_test_kotlin directory`() {
        val root = tempModule()
        // No 'src/test/kotlin' created
        val result = runAudit(root)
        assertEquals(Status.PASS, result.status)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `FAIL - scheduler API without runTest`() {
        val root = tempModule()
        write(
            root, "src/test/kotlin/com/example/BadScheduler.kt",
            """
            package com.example
            import kotlinx.coroutines.test.StandardTestDispatcher
            class BadScheduler { val d = StandardTestDispatcher() }
            """.trimIndent()
        )

        val result = runAudit(root)
        assertEquals(Status.FAIL, result.status)
        assertEquals(1, result.findings.size)
        assertTrue(result.findings.first().message.contains("Scheduler APIs"))
    }

    @Test
    fun `PASS - scheduler API present but runTest exists in file`() {
        val root = tempModule()
        write(
            root, "src/test/kotlin/com/example/SchedulerOk.kt",
            """
            package com.example
            import kotlinx.coroutines.test.runTest
            import kotlinx.coroutines.test.UnconfinedTestDispatcher
            class SchedulerOk {
                fun t() = runTest {
                    val d = UnconfinedTestDispatcher()
                }
            }
            """.trimIndent()
        )

        val result = runAudit(root)
        assertEquals(Status.PASS, result.status)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `FAIL - main dispatcher used without any configured rule`() {
        val root = tempModule()
        write(
            root, "src/test/kotlin/com/example/MainNoRule.kt",
            """
            package com.example
            import kotlinx.coroutines.Dispatchers
            class MainNoRule { fun x() { val m = Dispatchers.Main } }
            """.trimIndent()
        )

        val result = runAudit(root)
        assertEquals(Status.FAIL, result.status)
        assertEquals(1, result.findings.size)
        assertTrue(result.findings.first().message.contains("Main dispatcher"))
    }

    @Test
    fun `PASS - main dispatcher with configured rule referenced by simple name`() {
        val root = tempModule()
        write(
            root, "src/test/kotlin/com/example/MainWithRuleSimple.kt",
            """
            package com.example
            import kotlinx.coroutines.Dispatchers
            import com.example.testing.MainDispatcherRule
            class MainWithRuleSimple {
                private val rule = MainDispatcherRule()
                fun x() { val m = Dispatchers.Main }
            }
            """.trimIndent()
        )

        val result = runAudit(
            root,
            rules = listOf("com.example.testing.MainDispatcherRule")
        )
        assertEquals(Status.PASS, result.status)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `PASS - main dispatcher with configured rule referenced by FQCN only`() {
        val root = tempModule()
        write(
            root, "src/test/kotlin/com/example/MainWithRuleFqcn.kt",
            """
            package com.example
            import kotlinx.coroutines.Dispatchers
            class MainWithRuleFqcn {
                private val rule = com.example.testing.MainDispatcherRule()
                fun x() { val m = Dispatchers.Main }
            }
            """.trimIndent()
        )

        val result = runAudit(
            root,
            rules = listOf("com.example.testing.MainDispatcherRule")
        )
        assertEquals(Status.PASS, result.status)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `PASS - allowlisted file is skipped entirely`() {
        val root = tempModule()
        write(
            root, "src/test/kotlin/legacy/NeedsIgnore.kt",
            """
            package legacy
            import kotlinx.coroutines.Dispatchers
            class NeedsIgnore { fun x() { val m = Dispatchers.Main } }
            """.trimIndent()
        )

        val result = runAudit(
            root,
            allowFiles = listOf("**/legacy/**")
        )
        assertEquals(Status.PASS, result.status)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `FAIL - both issues in one file yield two findings`() {
        val root = tempModule()
        write(
            root, "src/test/kotlin/com/example/TwoFindings.kt",
            """
            package com.example
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.test.StandardTestDispatcher
            class TwoFindings {
                val d = StandardTestDispatcher() // scheduler API
                fun x() { val m = Dispatchers.Main } // main dispatcher
            }
            """.trimIndent()
        )

        val result = runAudit(root)
        assertEquals(Status.FAIL, result.status)
        assertEquals(2, result.findings.size)
        assertTrue(result.findings.any { it.message.contains("Scheduler APIs") })
        assertTrue(result.findings.any { it.message.contains("Main dispatcher") })
    }

    @Test
    fun `THROWS - missing moduleDir`() {
        val missing = File("___definitely_not_here___/x")
        assertThrows(IllegalStateException::class.java) {
            runAudit(missing)
        }
    }
}
