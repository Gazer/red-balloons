package com.redballoons.plugin.model

data class ExecutionResult(
    val success: Boolean,
    val output: String,
    val error: String,
    val exitCode: Int,
)