package com.redballoons.plugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindowManager
import com.redballoons.plugin.ops.Search
import com.redballoons.plugin.ops.Search.invoke
import com.redballoons.plugin.ops.Vibe
import com.redballoons.plugin.prompt.ContextData
import com.redballoons.plugin.prompt.Prompt
import com.redballoons.plugin.services.OpencodeService
import com.redballoons.plugin.ui.PromptPopup
import com.redballoons.plugin.ui.SearchResultsPanel

/**
 * Vibe Mode Action (Ctrl+Alt+V)
 * Lets the AI freely modify the project.
 * Files are modified directly on disk, then the IDE refreshes.
 */
class VibeModeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)

        val popup = PromptPopup(
            project = project,
            mode = OpencodeService.ExecutionMode.VIBE,
            editor = editor
        ) { prompt ->
            executeVibeMode(e, prompt)
        }

        popup.show()
    }

    private fun executeVibeMode(e: AnActionEvent, userPrompt: String) {
        val project = e.project ?: return
        val context = Prompt.vibe(project)

        context.userPrompt = userPrompt

        Vibe(context) {
            val searchData: ContextData.Vibe = context.data as ContextData.Vibe
            if (searchData.quickFixItems.isEmpty()) {
                Messages.showInfoMessage(
                    project,
                    "No results found for: $userPrompt",
                    "Search Results"
                )
            } else {
                VirtualFileManager.getInstance().asyncRefresh {
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Red Balloons Results")

                    toolWindow?.let { tw ->
                        tw.show {
                            val content = tw.contentManager.getContent(0)
                            val panel = content?.component as? SearchResultsPanel
                            panel?.setResults(searchData.quickFixItems, userPrompt)
                        }
                    }
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}