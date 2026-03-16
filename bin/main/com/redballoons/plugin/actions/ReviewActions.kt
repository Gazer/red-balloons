package com.redballoons.plugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.redballoons.plugin.prompt.ContextData
import com.redballoons.plugin.services.OpencodeService
import com.redballoons.plugin.services.SessionManager
import com.redballoons.plugin.ui.PromptPopup

abstract class SessionAction : AnAction(), DumbAware {
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor == null) {
            e.presentation.isEnabled = false
            return
        }
        val session = SessionManager.getInstance().findSessionForReview(editor)
        e.presentation.isEnabled = session != null
    }

    protected fun getSession(e: AnActionEvent): SessionManager.Session? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val sessionFromContext = e.dataContext.getData("session") as? SessionManager.Session
        if (sessionFromContext != null) return sessionFromContext
        return SessionManager.getInstance().findSessionForReview(editor)
    }
}

class AcceptAction : SessionAction() {
    override fun actionPerformed(e: AnActionEvent) {
        OpencodeService.getInstance().log("AcceptAction triggered")
        val session = getSession(e) ?: run {
            OpencodeService.getInstance().log("AcceptAction: No session found")
            return
        }
        SessionManager.getInstance().applySession(session)
    }
}

class RejectAction : SessionAction() {
    override fun actionPerformed(e: AnActionEvent) {
        OpencodeService.getInstance().log("RejectAction triggered")
        val session = getSession(e) ?: run {
            OpencodeService.getInstance().log("RejectAction: No session found")
            return
        }
        SessionManager.getInstance().discardSession(session)
    }
}

class RefineAction : SessionAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val session = getSession(e) ?: return
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val visualData = session.context.data as? ContextData.Visual ?: return
        val oldSelectionContext = visualData.selectionContext
        
        // Use current range marker position for refinement
        val selectionContext = if (session.rangeMarker.isValid) {
            val startLine = editor.document.getLineNumber(session.rangeMarker.startOffset) + 1
            val endLine = editor.document.getLineNumber(session.rangeMarker.endOffset) + 1
            oldSelectionContext.copy(
                selectionStart = session.rangeMarker.startOffset,
                selectionEnd = session.rangeMarker.endOffset,
                startLine = startLine,
                endLine = endLine,
                fileContent = editor.document.text,
                selectedText = editor.document.getText(com.intellij.openapi.util.TextRange(session.rangeMarker.startOffset, session.rangeMarker.endOffset))
            )
        } else {
            oldSelectionContext
        }
        
        val oldPrompt = session.lastUserPrompt.ifBlank { session.context.userPrompt }

        val popup = PromptPopup(
            project = project,
            mode = OpencodeService.ExecutionMode.SELECTION,
            editor = editor,
            initialText = oldPrompt
        ) { userPrompt ->
            SelectionModeAction.refineSession(project, editor, session, selectionContext, userPrompt)
        }
        popup.show()
    }
}
