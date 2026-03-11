package com.redballoons.plugin.model

data class SearchResult(
    val filePath: String,
    val lineNumber: Int,
    val columnNumber: Int,
    val highlightLines: Int,
    val notes: String,
) {
    val fileName: String
        get() = filePath.substringAfterLast("/")

    val displayPath: String
        get() {
            // Show last 2-3 path components for context
            val parts = filePath.split("/")
            return if (parts.size > 3) {
                ".../" + parts.takeLast(3).joinToString("/")
            } else {
                filePath
            }
        }
}