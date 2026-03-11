package com.redballoons.plugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.redballoons.plugin.services.OpencodeService
import com.redballoons.plugin.ui.PromptPopup

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

    private fun executeVibeMode(e: AnActionEvent, prompt: String) {
        val project = e.project ?: return

        // TODO bring back vibe
//        val service = OpencodeService.getInstance()
//
//        service.execute(
//            project = project,
//            prompt = prompt,
//            mode = OpencodeService.ExecutionMode.VIBE,
//            workingDirectory = project.basePath
//        ) { result ->
//            // Refresh the VFS so IDE detects disk changes
//            VirtualFileManager.getInstance().asyncRefresh {
//                if (!result.success) {
//                    val errorMsg = result.error.ifBlank { "Vibe mode completed with errors" }
//                    Messages.showErrorDialog(project, errorMsg, "Opencode Error")
//                } else {
//                    // Optionally show a success notification
//                    JBPopupFactory.getInstance()
//                        .createMessage("Vibe mode completed!")
//                        .showInFocusCenter()
//                }
//            }
//        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}