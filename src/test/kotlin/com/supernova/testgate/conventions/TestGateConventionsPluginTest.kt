package com.supernova.testgate.conventions

import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class TestGateConventionsPluginTest {

    private fun project() = ProjectBuilder.builder().build().also {
        // Apply the convention plugin BY CLASS (ProjectBuilder cannot resolve IDs)
        it.pluginManager.apply(TestGateConventionsPlugin::class.java)
        (it as ProjectInternal).evaluate()
    }


    @Test
    fun `publishes isAndroid default false`() {
        val p = project()
        assertEquals(false, p.extensions.extraProperties["isAndroid"])
    }

    @Test
    fun `jvm jacocoTestReport writes to TestGate path and disables html csv`() {
        val p = project()
        val t = p.tasks.named("jacocoTestReport").get() as JacocoReport
        val xml = t.reports.xml.outputLocation.get().asFile
        val normalized = xml.path.replace(File.separatorChar, '/')
        assertTrue(
            normalized.endsWith("build/reports/jacoco/testDebugUnitTestReport/testDebugUnitTestReport.xml"),
            "Unexpected XML path: $normalized"
        )
        assertTrue(t.reports.xml.required.get())
        assertFalse(t.reports.html.required.get())
        assertFalse(t.reports.csv.required.get())
    }

}
