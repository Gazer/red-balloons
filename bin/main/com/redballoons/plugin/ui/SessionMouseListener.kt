package com.redballoons.plugin.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.redballoons.plugin.services.SessionManager
import java.awt.event.MouseEvent

class SessionMouseListener : EditorMouseListener, EditorMouseMotionListener {
    private var lastHoveredInlay: com.intellij.openapi.editor.Inlay<*>? = null

    override fun mouseMoved(e: EditorMouseEvent) {
        val editor = e.editor
        val point = e.mouseEvent.point
        val sessionAtPoint = SessionManager.getInstance().findSessionByPoint(editor, point)
        val inlay = sessionAtPoint?.inlay ?: editor.inlayModel.getElementAt(point)

        if (sessionAtPoint != null) {
            SessionManager.getInstance().setActiveReviewSession(sessionAtPoint)
        }
        
        if (inlay != lastHoveredInlay) {
            (lastHoveredInlay?.renderer as? DiffInlayRenderer)?.setHoveredAction(null)
            lastHoveredInlay?.repaint()
            lastHoveredInlay = inlay
            SessionManager.getInstance().setHoveredInlay(inlay)
        }

        val renderer = inlay?.renderer as? DiffInlayRenderer
        if (renderer != null) {
            val action = renderer.getActionAt(point.x, point.y, inlay)
            renderer.setHoveredAction(action)
            inlay.repaint()
        }
    }

    override fun mouseClicked(e: EditorMouseEvent) {
        val editor = e.editor
        val point = e.mouseEvent.point
        val session = SessionManager.getInstance().findSessionByPoint(editor, point) ?: run {
            val inlay = editor.inlayModel.getElementAt(point) ?: return
            SessionManager.getInstance().findSessionByInlay(inlay)
        } ?: run {
            com.redballoons.plugin.services.OpencodeService.getInstance().log("Mouse clicked but no session found for inlay")
            return
        }

        SessionManager.getInstance().setActiveReviewSession(session)

        val inlay = session.inlay ?: return
        val renderer = inlay.renderer as? DiffInlayRenderer ?: return

        val actionName = renderer.getActionAt(point.x, point.y, inlay)
        com.redballoons.plugin.services.OpencodeService.getInstance().log("Mouse clicked on action: $actionName")
        if (actionName != null) {
            val actionId = actionIdForName(actionName)
            triggerAction(actionId, editor, session, e)
        } else if (e.mouseEvent.button == MouseEvent.BUTTON1) {
            showFallbackPopup(editor, session, e)
        }
    }

    private fun actionIdForName(actionName: String): String? {
        return when (actionName) {
            "Yes" -> "RedBalloons.Accept"
            "No" -> "RedBalloons.Reject"
            "Refine" -> "RedBalloons.Refine"
            else -> null
        }
    }

    private fun triggerAction(
        actionId: String?,
        editor: Editor,
        session: SessionManager.Session,
        e: EditorMouseEvent,
    ) {
        if (actionId == null) return
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        val dataContext = DataContext { dataId ->
            when (dataId) {
                CommonDataKeys.EDITOR.name -> editor
                CommonDataKeys.PROJECT.name -> editor.project
                "session" -> session
                else -> null
            }
        }
        val event = AnActionEvent.createFromAnAction(action, e.mouseEvent, ActionPlaces.EDITOR_INLAY, dataContext)
        action.actionPerformed(event)
    }

    private fun showFallbackPopup(editor: Editor, session: SessionManager.Session, e: EditorMouseEvent) {
        val isMac = System.getProperty("os.name").contains("Mac", ignoreCase = true)
        val cmdKey = if (isMac) "Cmd" else "Ctrl"
        val options = listOf(
            "Accept ($cmdKey+Y)",
            "Reject ($cmdKey+N)",
            "Refine ($cmdKey+R)",
        )

        val popup = JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<String>("AI Diff Actions", options) {
            override fun onChosen(selectedValue: String?, finalChoice: Boolean): PopupStep<*>? {
                val actionId = when (selectedValue) {
                    options[0] -> "RedBalloons.Accept"
                    options[1] -> "RedBalloons.Reject"
                    options[2] -> "RedBalloons.Refine"
                    else -> null
                }
                triggerAction(actionId, editor, session, e)
                return FINAL_CHOICE
            }
        })

        popup.showInBestPositionFor(editor)
    }
}
