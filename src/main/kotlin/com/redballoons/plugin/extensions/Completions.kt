package com.redballoons.plugin.extensions

import com.redballoons.plugin.model.AutoCompleteSuggestion

object Completions {
    private var providers = emptyList<CompletionProvider>()

    fun setup(providers: List<CompletionProvider>) {
        this.providers = providers
    }

    fun getTriggers(): List<Char> = providers.map { it.trigger }

    fun parse(promptText: String): List<Map<String, String>> {
        val refs = mutableListOf<Map<String, String>>()
        for (provider in providers) {
            val pattern = Regex(Regex.escape(provider.trigger.toString()) + "\\S+")
            for (match in pattern.findAll(promptText)) {
                val word = match.value
                val token = word.substring(1)
                if (provider.isValid(token)) {
                    val content = provider.resolve(token)
                    if (content != null) {
                        refs.add(mapOf("content" to content))
                    }
                }
            }
        }
        return refs
    }

    fun getSuggestions(matchedTrigger: Char, prefix: String): List<AutoCompleteSuggestion> {
        for (provider in providers) {
           if (provider.trigger == matchedTrigger) {
               return provider.getFiles(prefix)
           }
        }
        return emptyList()
    }
}