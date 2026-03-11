package com.redballoons.plugin.ops

import com.redballoons.plugin.prompt.Context
import com.redballoons.plugin.prompt.ContextData
import com.redballoons.plugin.prompt.PromptStrings
import com.redballoons.plugin.services.OpencodeService

object OverRange {
    operator fun invoke(context: Context, cb: (OpencodeService.ExecutionResult) -> Unit) {
        val data = context.data as ContextData.Visual
        val systemPrompt = PromptStrings.visualSelection(data.selectionContext)

        val prompt = MakePrompt(context, systemPrompt)

        context.addPromptContent(prompt)
        //context:add_references(refs)
        // TODO: cleanup is the function that remove the visual indicator for
        // the seleccion
        //context:add_clean_up(clean_up)

        // TODO: here we start the visual indicator of the processing
//        top_status:start()
//        bottom_status:start()
        context.startRequest() { result ->
            cb(result)
        }
    }
}