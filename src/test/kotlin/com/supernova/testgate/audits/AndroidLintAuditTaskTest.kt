// src/test/kotlin/com/supernova/testgate/audits/AndroidLintAuditTaskTest.kt
package com.supernova.testgate.audits

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class AndroidLintAuditTaskTest {

    private fun write(file: File, text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `parses only ERROR or FATAL severities`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("lintAudit", AndroidLintAuditTask::class.java)

        val xml = project.layout.buildDirectory.file("reports/lint-results-debug.xml").get().asFile
        write(xml, """
            <issues>
              <issue id="W" severity="Warning" message="w">
                <location file="${'$'}{project.projectDir}/app/src/main/java/com/acme/W.kt" line="1"/>
              </issue>
              <issue id="E" severity="Error" message="e">
                <location file="${'$'}{project.projectDir}/app/src/main/java/com/acme/E.kt" line="2"/>
              </issue>
              <issue id="F" severity="Fatal" message="f">
                <location file="${'$'}{project.projectDir}/app/src/main/java/com/acme/F.kt" line="3"/>
              </issue>
            </issues>
        """.trimIndent())

        val findings = task.parseErrorFindings(xml)
        assertEquals(2, findings.size)
        assertTrue(findings.any { it.message == "e" })
        assertTrue(findings.any { it.message == "f" })
    }

    @Test
    fun `whitelist filters by import-like pattern`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("lintAudit", AndroidLintAuditTask::class.java)

        val xml = project.layout.buildDirectory.file("reports/lint-results-debug.xml").get().asFile
        write(xml, """
            <issues>
              <issue id="E1" severity="Error" message="one">
                <location file="${'$'}{project.projectDir}/app/src/main/java/com/acme/foo/One.kt" line="1"/>
              </issue>
              <issue id="E2" severity="Error" message="two">
                <location file="${'$'}{project.projectDir}/app/src/main/java/com/acme/bar/Two.kt" line="2"/>
              </issue>
            </issues>
        """.trimIndent())

        val all = task.parseErrorFindings(xml)
        val exceptions = task.readExceptions("com.acme.foo.*")

        val remaining = all.filterNot { f ->
            val fqcn = f.filePath?.let { task.deriveFqcn(it) }
            fqcn != null && exceptions.any { it.matches(fqcn) }
        }

        assertEquals(1, remaining.size)
        assertEquals("two", remaining[0].message)
    }

    @Test
    fun `tolerance gates PASS vs FAIL`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("lintAudit", AndroidLintAuditTask::class.java)
        task.tolerance.set(1) // allow one error

        val xml = project.layout.buildDirectory.file("reports/lint-results-debug.xml").get().asFile
        write(xml, """
            <issues>
              <issue id="E1" severity="Error" message="one">
                <location file="${'$'}{project.projectDir}/app/src/main/java/com/acme/A.kt"/>
              </issue>
              <issue id="E2" severity="Error" message="two">
                <location file="${'$'}{project.projectDir}/app/src/main/java/com/acme/B.kt"/>
              </issue>
            </issues>
        """.trimIndent())

        // exercise via TaskAction
        task.runAudit()
        val r1 = task.lastResult!!
        assertEquals(2, r1.findingCount)
        assertEquals(Status.FAIL, r1.status)

        // exercise via check(callback)
        var delivered: CheckpointResult? = null
        task.check { delivered = it }
        assertNotNull(delivered)
        assertEquals(Status.FAIL, delivered!!.status)
        assertEquals(r1.findingCount, delivered!!.findingCount)
    }
}
