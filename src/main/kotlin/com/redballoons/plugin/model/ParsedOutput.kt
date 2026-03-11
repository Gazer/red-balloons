package com.redballoons.plugin.model

data class ParsedOutput(
    val imports: List<String>,
    val content: String,
) {
    companion object {
        fun parse(raw: String): ParsedOutput {
            val importsRegex = Regex("<IMPORTS>\\s*([\\s\\S]*?)\\s*</IMPORTS>", RegexOption.IGNORE_CASE)
            val contentRegex = Regex("<CONTENT>\\s*([\\s\\S]*?)\\s*</CONTENT>", RegexOption.IGNORE_CASE)

            val importsMatch = importsRegex.find(raw)
            val contentMatch = contentRegex.find(raw)

            val imports = importsMatch?.groupValues?.get(1)
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            val content = contentMatch?.groupValues?.get(1)?.trim() ?: raw.trim()

            return ParsedOutput(imports, content)
        }
    }
}