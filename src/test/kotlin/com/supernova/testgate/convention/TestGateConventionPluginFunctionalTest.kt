package com.supernova.testgate.convention

import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test as JUnitTest

class TestGateConventionPluginFunctionalTest {

    private fun project() = ProjectBuilder.builder().build().also {
        it.plugins.apply(KotlinPluginWrapper::class.java)
        it.plugins.apply(TestGateConventionPlugin::class.java)
    }

    @JUnitTest
    fun `applies quality plugins`() {
        val p = project()
        assertTrue(p.pluginManager.hasPlugin("jacoco"))
        assertTrue(p.pluginManager.hasPlugin("io.gitlab.arturbosch.detekt"))
        assertDoesNotThrow { p.tasks.named("detekt").get() }
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

