package com.redballoons.plugin.handlers

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.redballoons.plugin.services.SessionManager

class ReviewTypedHandler : TypedHandlerDelegate() {
    override fun beforeCharTyped(
        c: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
        fileType: FileType,
    ): Result {
        val session = SessionManager.getInstance().getSessionForShortcut(editor) ?: return Result.DEFAULT
        val actionId = shortcutActionId(c) ?: return Result.DEFAULT

        SessionManager.getInstance().executeActionForSession(actionId, editor, session)
        return Result.STOP
    }

    private fun shortcutActionId(c: Char): String? {
        return when (c.lowercaseChar()) {
            'y' -> "RedBalloons.Accept"
            'n' -> "RedBalloons.Reject"
            'r' -> "RedBalloons.Refine"
            else -> null
        }
    }
}
