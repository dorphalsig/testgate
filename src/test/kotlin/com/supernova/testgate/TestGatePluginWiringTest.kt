package com.supernova.testgate

import org.gradle.api.Task
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test as JUnitTest

class TestGatePluginWiringTest {

    private fun newProject(): org.gradle.api.Project {
        val p = ProjectBuilder.builder().build()
        // Apply the core plugin directly (convention plugin is heavier and requires external plugins)
        p.plugins.apply(TestGatePlugin::class.java)
        return p
    }

    private fun Task.finalizerNames(): Set<String> =
        // Gradle exposes the finalizers as a TaskDependency
        this.finalizedBy
            .getDependencies(this)
            .map { it.name }
            .toSet()

    @JUnitTest
    fun `compilation audit task is registered`() {
        val p = newProject()
        // Creating any compilation-like task should register the audit provider
        p.tasks.create("compileDebugKotlin")
        assertDoesNotThrow { p.tasks.named("compilationAudit") }
    }

    @JUnitTest
    fun `detekt audit task is registered`() {
        val p = newProject()
        p.tasks.create("detekt")

        // The provider for detektAudit must be registered (lazy OK)
        assertDoesNotThrow { p.tasks.named("detektAudit") }
    }

    @JUnitTest
    fun `lint audit task is registered`() {
        val p = newProject()
        p.tasks.create("lintDebug")

        // The provider for lintDebugAudit must be registered (lazy OK)
        assertDoesNotThrow { p.tasks.named("lintDebugAudit") }
    }

    @JUnitTest
    fun `unit test tasks are finalized by testGateAuditsTests`() {
        val p = newProject()
        val unit = p.tasks.create("test", Test::class.java)
        val androidUnit = p.tasks.create("testDebugUnitTest", Test::class.java)

        // Realize the audit task so finalizedBy becomes visible
        p.tasks.named("testGateAuditsTests").get()
        val audit = p.tasks.findByName("testGateAuditsTests")
        assertNotNull(audit, "testGateAuditsTests should be registered")

        assertTrue("testGateAuditsTests" in unit.finalizerNames(), "test should finalize testGateAuditsTests")
        assertTrue(
            "testGateAuditsTests" in androidUnit.finalizerNames(),
            "testDebugUnitTest should finalize testGateAuditsTests"
        )
    }

    @JUnitTest
    fun `jacoco audit task is registered`() {
        val p = newProject()
        p.tasks.create("jacocoTestReport")
        assertDoesNotThrow { p.tasks.named("coverageBranchesAudit") }
    }

    @JUnitTest
    fun `audit tasks are registered for compilation wiring`() {
        val p = newProject()
        p.tasks.create("compileDebugKotlin")

        // Ensure providers are registered (do not realize tasks that have side-effects)
        assertDoesNotThrow { p.tasks.named("runFixturesAudit") }
        assertDoesNotThrow { p.tasks.named("structureAudit") }
        assertDoesNotThrow { p.tasks.named("testStackPolicyAudit") }
        assertDoesNotThrow { p.tasks.named("harnessReuseAudit") }
        assertDoesNotThrow { p.tasks.named("auditsSqlFts") }
        assertDoesNotThrow { p.tasks.named("compilationAudit") }
    }
}
