package com.redballoons.plugin.ops

import com.redballoons.plugin.model.SearchResult
import com.redballoons.plugin.prompt.Context
import com.redballoons.plugin.prompt.ContextData
import com.redballoons.plugin.prompt.PromptStrings

object Search {
    operator fun invoke(context: Context, cb: () -> Unit) {
        val systemPrompt = PromptStrings.semanticSearch()

        val prompt = MakePrompt(context, systemPrompt)
        context.addPromptContent(prompt)

        context.startRequest { result ->
            val searchResults = parseSearchOutput(
                result.output,
                context.workingDirectory
            )
            context.data = (context.data as ContextData.Search).copy(
                quickFixItems = searchResults
            )
            cb()
        }
    }

    private fun parseSearchOutput(output: String, projectBasePath: String): List<SearchResult> {
        return output.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains(":") }
            .mapNotNull { line ->
                try {
                    // Format: /path/to/file:line:col,lines,notes
                    // Need to handle paths with colons carefully (Windows paths, etc.)
                    val regex = Regex("""^(.+):(\d+):(\d+),(\d+),(.*)$""")
                    val match = regex.find(line)

                    if (match != null) {
                        val (filePath, lineStr, colStr, highlightStr, notes) = match.destructured

                        val absolutePath = if (filePath.startsWith("/")) {
                            filePath
                        } else {
                            "$projectBasePath/$filePath"
                        }

                        SearchResult(
                            filePath = absolutePath,
                            lineNumber = lineStr.toInt(),
                            columnNumber = colStr.toInt(),
                            highlightLines = highlightStr.toInt(),
                            notes = notes.trim()
                        )
                    } else {
                        // Fallback: try simpler format path:line:description
                        val parts = line.split(":", limit = 3)
                        if (parts.size >= 2) {
                            val filePath = if (parts[0].startsWith("/")) {
                                parts[0]
                            } else {
                                "$projectBasePath/${parts[0]}"
                            }
                            SearchResult(
                                filePath = filePath,
                                lineNumber = parts[1].toIntOrNull() ?: 1,
                                columnNumber = 1,
                                highlightLines = 1,
                                notes = if (parts.size > 2) parts[2].trim() else ""
                            )
                        } else null
                    }
                } catch (e: Exception) {
                    null
                }
            }
    }
}