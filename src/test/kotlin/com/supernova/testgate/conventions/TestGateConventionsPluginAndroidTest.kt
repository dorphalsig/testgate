package com.supernova.testgate.conventions

import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class TestGateConventionsPluginAndroidTest {

    private fun project() = ProjectBuilder.builder().build().also {
        it.pluginManager.apply("com.android.application")
        it.pluginManager.apply(TestGateConventionsPlugin::class.java)
        (it as ProjectInternal).evaluate()
    }

    @Test
    fun `android conventions configure lint jacoco and runner`() {
        val p = project()
        assertEquals(true, p.extensions.extraProperties["isAndroid"])
        assertTrue(p.pluginManager.hasPlugin("de.mannodermaus.android-junit5"))

        val androidExt = p.extensions.getByName("android") as FakeAndroidAppPlugin.AndroidExtension
        assertEquals("de.mannodermaus.junit5.AndroidJUnit5", androidExt.defaultConfig.testInstrumentationRunner)

        val lint = p.tasks.named("lintDebug").get() as FakeAndroidAppPlugin.FakeLintTask
        val expectedLint = p.layout.buildDirectory.file("reports/lint-results-debug.xml").get().asFile
        assertEquals(expectedLint, lint.xmlOutput)
        lint.actions.forEach { it.execute(lint) }
        assertTrue(expectedLint.exists())

        val exec = p.layout.buildDirectory.file("jacoco/testDebugUnitTest.exec").get().asFile
        exec.parentFile.mkdirs()
        exec.writeText("")

        val jacoco = p.tasks.named("jacocoTestDebugUnitTestReport").get() as JacocoReport
        val xml = jacoco.reports.xml.outputLocation.get().asFile
        xml.parentFile.mkdirs()
        xml.writeText("<report/>")
        val normalized = xml.path.replace(File.separatorChar, '/')
        assertTrue(normalized.endsWith("build/reports/jacoco/testDebugUnitTestReport/testDebugUnitTestReport.xml"))
        assertTrue(xml.exists())
    }
}
