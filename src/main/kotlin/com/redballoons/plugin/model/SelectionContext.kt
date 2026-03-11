package com.redballoons.plugin.model

data class SelectionContext(
    val filePath: String,
    val fileName: String,
    val startLine: Int,
    val endLine: Int,
    val selectedText: String,
    val fileContent: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)