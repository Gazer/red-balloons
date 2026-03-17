package com.redballoons.plugin.ops

import com.redballoons.plugin.model.SearchResult
import com.redballoons.plugin.prompt.Context
import com.redballoons.plugin.prompt.ContextData
import com.redballoons.plugin.prompt.PromptStrings

object Search {
    operator fun invoke(context: Context, cb: () -> Unit) {
        val systemPrompt = PromptStrings.semanticSearch()

        val (prompt, refs) = MakePrompt(context, systemPrompt)
        context.addPromptContent(prompt)
        context.addReferences(refs)

        context.startRequest { result ->
            val searchResults = SearchResult.parseSearchOutput(
                result.output,
                context.workingDirectory
            )
            context.data = (context.data as ContextData.Search).copy(
                quickFixItems = searchResults
            )
            cb()
        }
    }
}