package com.redballoons.plugin.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.redballoons.plugin.services.SessionManager

class ReviewActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
        val editor = CommonDataKeys.EDITOR.getData(context) ?: return null
        val session = SessionManager.getInstance().getSessionForShortcut(editor) ?: return null
        SessionManager.getInstance().setActiveReviewSession(session)

        val promoted = actions.filter {
            it is AcceptAction || it is RejectAction || it is RefineAction
        }
        return promoted.ifEmpty { null }
    }
}
