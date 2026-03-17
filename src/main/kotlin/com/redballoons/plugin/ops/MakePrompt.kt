package com.redballoons.plugin.ops

import com.redballoons.plugin.extensions.Completions
import com.redballoons.plugin.prompt.Context
import com.redballoons.plugin.prompt.PromptStrings

object MakePrompt {
    operator fun invoke(context: Context, basePrompt: String): Pair<String, List<Map<String, String>>> {
        val fullPrompt = PromptStrings.prompt(context.userPrompt, basePrompt)

        val refs = Completions.parse(context.userPrompt)

        return fullPrompt to refs
    }
}