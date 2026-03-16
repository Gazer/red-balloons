package com.redballoons.plugin.prompt

import com.intellij.openapi.project.Project
import com.redballoons.plugin.model.SelectionContext
import com.redballoons.plugin.settings.RedBalloonsSettings

object Prompt {
    fun visual(project: Project, selectionContext: SelectionContext): Context {
        val settings = RedBalloonsSettings.getInstance()
        val context = Context(
            workingDirectory = project.basePath ?: ".",
            model = settings.modelName,
        )

        context.operation = Operation.VISUAL
        context.data = ContextData.Visual(
            project = project,
            fullPath = selectionContext.filePath,
            fileType = "",
            selectionContext = selectionContext,
        )

        return context
    }

    fun search(project: Project): Context {
        val settings = RedBalloonsSettings.getInstance()
        val context = Context(
            workingDirectory = project.basePath ?: ".",
            model = settings.modelName,
        )
        context.operation = Operation.SEARCH
        context.data = ContextData.Search(
            project = project,
        )
        return context
    }

    fun vibe(project: Project):Context {
        val settings = RedBalloonsSettings.getInstance()
        val context = Context(
            workingDirectory = project.basePath ?: ".",
            model = settings.modelName,
        )
        context.operation = Operation.VIBE
        context.data = ContextData.Vibe(
            project = project,
        )
        return context
    }
}