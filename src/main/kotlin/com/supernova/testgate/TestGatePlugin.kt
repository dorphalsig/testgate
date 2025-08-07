package com.supernova.testgate

import org.gradle.api.Plugin
import org.gradle.api.Project

class TestGatePlugin : Plugin<Project> {
    companion object {
        private var registered = false
    }

    override fun apply(project: Project) {
        if (!registered) {
            registered = true
            val collector = ErrorCollectorListener()
            project.gradle.addListener(collector)

            project.gradle.taskGraph.whenReady {
                val hasRelevantTasks = allTasks.any { task ->
                    task.name.contains("test", ignoreCase = true) ||
                            task.name.contains("compile", ignoreCase = true) ||
                            task.name.contains("jacoco", ignoreCase = true) ||
                            task.name.contains("detekt", ignoreCase = true)
                }

                if (hasRelevantTasks) {
                    project.gradle.buildFinished {
                        ReportCoordinator.handleBuildFinished(project.rootProject, collector.allResults)
                    }
                }
            }
        }
    }
}