package com.supernova.testgate
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.tasks.TaskState
import org.gradle.api.Task
class ErrorCollectorListener : TaskExecutionListener {
    private val _results = mutableListOf<TaskResult>()
    val allResults: List<TaskResult> get() = _results
    override fun beforeExecute(task: Task) {
        // no-op; we only care about afterExecute
    }
    override fun afterExecute(task: Task, state: TaskState) {
        _results += TaskResult(task.path, state.failure)
    }
}
