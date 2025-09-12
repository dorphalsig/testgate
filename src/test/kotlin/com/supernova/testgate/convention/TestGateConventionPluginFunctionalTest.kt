package com.supernova.testgate.convention

import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test as JUnitTest

class TestGateConventionPluginCoverageTest {

    private fun project() = ProjectBuilder.builder().build().also {
        it.plugins.apply(KotlinPluginWrapper::class.java)
        it.plugins.apply(TestGateConventionPlugin::class.java)
    }

    @JUnitTest
    fun `applies quality plugins when available`() {
        val p = project()
        assertTrue(p.pluginManager.hasPlugin("jacoco"))
        assertFalse(
            p.pluginManager.hasPlugin("io.gitlab.arturbosch.detekt"),
            "detekt is optional and should not fail if absent",
        )
    }

    @JUnitTest
    fun `test task is finalized by jacoco`() {
        val p = project()
        val test = p.tasks.named("test", Test::class.java).get()
        assertDoesNotThrow { p.tasks.named("jacocoTestReport") }
        val finalizers = test.finalizedBy.getDependencies(test).map { it.name }
        assertTrue("jacocoTestReport" in finalizers)
    }
}

