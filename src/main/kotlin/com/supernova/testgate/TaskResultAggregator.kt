package com.supernova.testgate

data class TaskResult(
    val taskPath: String,
    val failure: Throwable?
)

class TaskResultAggregator(private val results: List<TaskResult>) {
    fun compileErrors(): List<TaskResult> =
        results.filter { it.taskPath.contains("compile", ignoreCase = true) && it.failure != null }

    fun testFailures(): List<TaskResult> =
        results.filter { it.taskPath.contains("test", ignoreCase = true) && it.failure != null }

    fun otherFailures(): List<TaskResult> =
        results.filter {
            it.failure != null &&
                    !it.taskPath.contains("compile", true) &&
                    !it.taskPath.contains("test", true)
        }
}
