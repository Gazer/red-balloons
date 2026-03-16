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

            var content = contentMatch?.groupValues?.get(1)?.trim()
            
            if (content == null) {
                // If no <CONTENT> tag, try to remove <IMPORTS> tag from raw if present
                content = if (importsMatch != null) {
                    raw.replace(importsMatch.groupValues[0], "").trim()
                } else {
                    raw.trim()
                }
            }
            
            // Final cleanup: remove potential markdown backticks if they surround the whole content
            if (content.startsWith("```") && content.endsWith("```")) {
                content = content.removeSurrounding("```")
                if (content.startsWith("kotlin\n") || content.startsWith("java\n")) {
                    content = content.substringAfter("\n")
                }
            }

            return ParsedOutput(imports, content.trim())
        }
    }
}