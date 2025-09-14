package com.supernova.testgate.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import java.io.File

class FakeAndroidAppPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.add("android", AndroidExtension())
        project.tasks.register("lintDebug", FakeLintTask::class.java)
        project.tasks.register("testDebugUnitTest", Test::class.java) {
            doLast {
                val exec = project.layout.buildDirectory.file("jacoco/testDebugUnitTest.exec").get().asFile
                exec.parentFile.mkdirs()
                exec.writeText("")
            }
        }
    }

    class AndroidExtension {
        val defaultConfig = DefaultConfig()
    }

    class DefaultConfig {
        var testInstrumentationRunner: String? = null
    }

    abstract class FakeLintTask : DefaultTask() {
        var xmlOutput: File? = null
        var htmlOutput: File? = null
        var textOutput: File? = null

        @TaskAction
        fun run() {
            xmlOutput?.let {
                it.parentFile.mkdirs()
                it.writeText("<issues/>")
            }
        }
    }
}

class FakeMannodermausPlugin : Plugin<Project> {
    override fun apply(project: Project) {}
}
