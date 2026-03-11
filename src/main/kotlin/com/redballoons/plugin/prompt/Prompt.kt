package com.redballoons.plugin.prompt

import com.intellij.openapi.project.Project
import com.redballoons.plugin.services.SelectionContext
import com.redballoons.plugin.settings.RedBalloonsSettings

object Prompt {
    fun visual(project: Project, selectionContext: SelectionContext): Context {
        val settings = RedBalloonsSettings.getInstance()
        val context = Context(
            workingDirectory = project.basePath ?: ".",
            model = settings.modelName,
            fullPath = selectionContext.filePath
        )

        context.operation = Operation.VISUAL
        context.data = ContextData.Visual(
            project, "", "", selectionContext,
        )

        return context
    }
}