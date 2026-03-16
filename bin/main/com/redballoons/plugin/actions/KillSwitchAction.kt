package com.redballoons.plugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.redballoons.plugin.services.OpencodeService

/**
 * Kill Switch Action (Ctrl+Alt+K)
 * Immediately terminates the currently running opencode process.
 */
class KillSwitchAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val service = OpencodeService.getInstance()

        if (service.killCurrentProcess()) {
            JBPopupFactory.getInstance()
                .createMessage("Opencode process stopped")
                .showInFocusCenter()
        } else {
            JBPopupFactory.getInstance()
                .createMessage("No active process to stop")
                .showInFocusCenter()
        }
    }

    override fun update(e: AnActionEvent) {
        val service = OpencodeService.getInstance()
        e.presentation.isEnabled = service.isRunning()
        e.presentation.text = if (service.isRunning()) {
            "Opencode: Stop (Running...)"
        } else {
            "Opencode: Stop Execution"
        }
    }
}