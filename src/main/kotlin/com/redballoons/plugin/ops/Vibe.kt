package com.redballoons.plugin.ops

import com.redballoons.plugin.model.SearchResult
import com.redballoons.plugin.prompt.Context
import com.redballoons.plugin.prompt.ContextData
import com.redballoons.plugin.prompt.PromptStrings

object Vibe {
    operator fun invoke(context: Context, cb: () -> Unit) {
        val (prompt, refs) = MakePrompt(context, PromptStrings.vibe())

        context.addPromptContent(prompt)
        context.addReferences(refs)
        // TODO: cleanup is the function that remove the visual indicator for
        // the seleccion
        //context:add_clean_up(clean_up)

        context.startRequest { result ->
            val changedFiles = SearchResult.parseSearchOutput(
                result.output,
                context.workingDirectory
            )
            context.data = (context.data as ContextData.Vibe).copy(
                quickFixItems = changedFiles
            )
            cb()
        }
    }
}