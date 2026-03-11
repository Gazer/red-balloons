package com.redballoons.plugin.ops

import com.redballoons.plugin.prompt.Context
import com.redballoons.plugin.prompt.PromptStrings

object MakePrompt {
    operator fun invoke(context: Context, basePrompt: String): String {
        val fullPrompt = PromptStrings.prompt(context.userPrompt, basePrompt)

        // TODO: Refs and additional rules

        return fullPrompt
    }
}