package com.supernova.testgate.audits

enum class Status{
    PASS, FAIL
}

data class Finding(
    val type: String,
    val filePath: String?,
    val line: Int?,
    val severity: String?,
    val message: String,
    val stacktrace: List<String> = emptyList()
)

data class CheckpointResult(
    val module: String,
    val name: String,
    val findings: List<Finding>,
    val tolerance: Int,
    val findingCount: Number,
    val status: Status
)

interface Audit {
    fun check(callback:(CheckpointResult) -> Unit)
}