package com.redballoons.plugin.services

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.redballoons.plugin.prompt.Context
import com.redballoons.plugin.ui.ProgressInlayRenderer
import com.redballoons.plugin.ui.SessionMouseListener
import java.awt.Point
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@Service
class SessionManager {
    private val lastReviewedSession = AtomicReference<String?>(null)

    data class Session(
        val context: Context,
        val editor: Editor,
        val rangeMarker: RangeMarker,
        var highlighter: RangeHighlighter? = null,
        var inlay: Inlay<*>? = null,
        var renderer: ProgressInlayRenderer? = null,
        var originalText: String = "",
        var aiOutput: String? = null,
        var imports: List<String> = emptyList(),
        var lastUserPrompt: String = context.userPrompt,
        var promptNumber: Int = 0,
        var pendingInlay: Inlay<*>? = null,
        var pendingRenderer: ProgressInlayRenderer? = null,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val activeEditors = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Editor, Boolean>())
    private val activeReviewSessionByEditor = ConcurrentHashMap<Editor, String>()
    private var hoveredInlay: Inlay<*>? = null

    fun getSessionForShortcut(editor: Editor): Session? {
        val hoveredSession = getHoveredSession(editor)
        if (hoveredSession != null) {
            setActiveReviewSession(hoveredSession)
            return hoveredSession
        }

        val caretSession = findSessionAtCaretOrAnchor(editor)
        if (caretSession != null) {
            setActiveReviewSession(caretSession)
            return caretSession
        }

        return null
    }

    fun executeActionForSession(actionId: String, editor: Editor, session: Session, dataContext: DataContext? = null) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        val actionContext = DataContext { dataId: String? ->
            when (dataId) {
                CommonDataKeys.EDITOR.name -> editor
                CommonDataKeys.PROJECT.name -> editor.project
                "session" -> session
                else -> dataContext?.getData(dataId ?: "")
            }
        }

        val event = AnActionEvent.createFromAnAction(action, null, "EDITOR", actionContext)
        action.actionPerformed(event)
    }

    fun setHoveredInlay(inlay: Inlay<*>?) {
        hoveredInlay = inlay
        val session = inlay?.let { findSessionByInlay(it) }
        if (session != null) {
            setActiveReviewSession(session)
        }
    }

    fun setActiveReviewSession(session: Session?) {
        if (session == null) {
            return
        }
        activeReviewSessionByEditor[session.editor] = session.context.xid
        lastReviewedSession.set(session.context.xid)
    }

    fun getHoveredInlay(): Inlay<*>? = hoveredInlay

    private fun getActiveReviewSession(editor: Editor): Session? {
        val sessionId = activeReviewSessionByEditor[editor] ?: return null
        val session = getSession(sessionId)
        if (session == null || !session.rangeMarker.isValid || session.aiOutput == null) {
            activeReviewSessionByEditor.remove(editor)
            return null
        }
        return session
    }

    fun assignPendingPromptNumber(session: Session): Int {
        session.promptNumber = 0
        refreshPendingPromptNumbers(session.editor)
        return session.promptNumber
    }

    fun refreshPendingPromptNumbers(editor: Editor) {
        val pendingSessions = sessions.values
            .filter { it.editor == editor && isPromptPending(it) }
            .sortedBy { it.rangeMarker.startOffset }

        pendingSessions.forEachIndexed { index, session ->
            val number = index + 1
            session.promptNumber = number
            session.renderer?.updateMessage("Implementing #$number...")
            session.pendingRenderer?.updateMessage("Implementing #$number...")
        }
    }

    fun startSession(session: Session) {
        sessions[session.context.xid] = session
        setActiveReviewSession(session)
        synchronized(activeEditors) {
            if (activeEditors.add(session.editor)) {
                val listener = SessionMouseListener()
                session.editor.addEditorMouseListener(listener)
                session.editor.addEditorMouseMotionListener(listener)
            }
        }
    }

    fun getSession(xid: String): Session? = sessions[xid]

    fun removeSession(xid: String): Session? {
        val removed = sessions.remove(xid)
        if (removed != null) {
            activeReviewSessionByEditor.remove(removed.editor)
            if (lastReviewedSession.get() == xid) {
                lastReviewedSession.set(null)
            }
            if (hoveredInlay == removed.inlay || hoveredInlay == removed.pendingInlay) {
                hoveredInlay = null
            }
            refreshPendingPromptNumbers(removed.editor)
        }
        return removed
    }

    fun findSessionAtCaret(editor: Editor): Session? {
        val offset = editor.caretModel.offset
        val document = editor.document
        val sessionsInEditor = sessions.values.filter { it.editor == editor && it.rangeMarker.isValid && it.aiOutput != null }

        if (sessionsInEditor.isEmpty()) return null
        
        // Priority 1: session at caret (inside range)
        val sessionAtCaret = sessionsInEditor.find {
            offset >= it.rangeMarker.startOffset && offset <= it.rangeMarker.endOffset
        }
        if (sessionAtCaret != null) return sessionAtCaret

        val caretLine = runCatching { document.getLineNumber(offset) }.getOrElse { -1 }
        if (caretLine == -1) return null

        return sessionsInEditor.find { session ->
            val inlay = session.inlay ?: return@find false
            val inlayOffset = inlay.offset.coerceIn(0, document.textLength)
            document.getLineNumber(inlayOffset) == caretLine
        }
    }

    fun findSessionAtCaretOrAnchor(editor: Editor): Session? {
        val sessionsInEditor = sessions.values.filter { it.editor == editor && it.rangeMarker.isValid && it.aiOutput != null }
        if (sessionsInEditor.isEmpty()) return null

        val offset = editor.caretModel.offset
        val document = editor.document
        val sessionAtCaret = sessionsInEditor.find {
            offset >= it.rangeMarker.startOffset && offset <= it.rangeMarker.endOffset
        }
        if (sessionAtCaret != null) return sessionAtCaret

        val caretLine = runCatching { document.getLineNumber(offset) }.getOrElse { -1 }
        if (caretLine == -1) return null

        return sessionsInEditor.find { session ->
            val inlay = session.inlay ?: return@find false
            val inlayOffset = inlay.offset.coerceIn(0, document.textLength)
            document.getLineNumber(inlayOffset) == caretLine
        }
    }

    fun findSessionByInlay(inlay: Inlay<*>): Session? {
        return sessions.values.find { it.inlay == inlay }
    }

    fun findSessionByPoint(editor: Editor, point: Point): Session? {
        return sessions.values.find { session ->
            if (session.editor != editor) return@find false
            val inlay = session.inlay ?: return@find false
            inlay.bounds?.contains(point) == true
        }
    }

    fun findSessionForReview(editor: Editor): Session? {
        return getSessionForShortcut(editor)
    }

    fun isSessionActive(session: Session): Boolean {
        return sessions[session.context.xid] === session
    }

    fun applySession(session: Session) {
        OpencodeService.getInstance().log("Applying session ${session.context.xid}")
        val project = session.editor.project ?: return
        val document = session.editor.document
        val aiOutput = session.aiOutput ?: return

        WriteCommandAction.runWriteCommandAction(project, "Opencode: Accept Changes", null, {
            if (!session.rangeMarker.isValid) return@runWriteCommandAction

            // Add imports
            if (session.imports.isNotEmpty()) {
                val importsText = session.imports.joinToString("\n") + "\n"
                val insertPosition = findImportInsertPosition(document.text)
                document.insertString(insertPosition, importsText)
            }

            // Replace content
            val start = session.rangeMarker.startOffset
            val end = session.rangeMarker.endOffset
            document.replaceString(start, end, aiOutput)

            session.rangeMarker.dispose()

            // Cleanup visuals
            cleanupSessionVisuals(session)

            // Post-process
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            psiDocumentManager.commitDocument(document)

            val virtualFile = FileDocumentManager.getInstance().getFile(document)
            if (virtualFile != null) {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile != null) {
                    OptimizeImportsProcessor(project, psiFile).run()
                    CodeStyleManager.getInstance(project).reformat(psiFile)
                }
            }
        })
        removeSession(session.context.xid)
    }

    fun discardSession(session: Session) {
        OpencodeService.getInstance().log("Discarding session ${session.context.xid}")
        cleanupSessionVisuals(session)
        session.rangeMarker.dispose()
        removeSession(session.context.xid)
    }

    private fun cleanupSessionVisuals(session: Session) {
        ApplicationManager.getApplication().invokeLater {
            session.renderer?.stopAnimation()
            session.inlay?.dispose()
            session.pendingRenderer?.stopAnimation()
            session.pendingInlay?.dispose()
            session.pendingRenderer = null
            session.pendingInlay = null
            session.highlighter?.let {
                session.editor.markupModel.removeHighlighter(it)
            }
            refreshPendingPromptNumbers(session.editor)
        }
    }

    private fun getHoveredSession(editor: Editor): Session? {
        val inlay = hoveredInlay ?: return null
        val session = findSessionByInlay(inlay) ?: return null
        if (session.editor != editor || session.aiOutput == null || !session.rangeMarker.isValid) {
            return null
        }
        return session
    }

    private fun isPromptPending(session: Session): Boolean {
        return session.rangeMarker.isValid && session.aiOutput == null && (session.pendingInlay != null || session.renderer != null)
    }

    private fun findImportInsertPosition(fileContent: String): Int {
        val lines = fileContent.lines()
        var lastImportEnd = 0
        var currentPos = 0
        var foundPackage = false
        var packageEnd = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("package ")) {
                foundPackage = true
                packageEnd = currentPos + line.length + 1
            } else if (trimmed.startsWith("import ")) {
                lastImportEnd = currentPos + line.length + 1
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*")) {
                if (lastImportEnd > 0) return lastImportEnd
                if (foundPackage) return packageEnd
                break
            }
            currentPos += line.length + 1
        }
        return if (lastImportEnd > 0) lastImportEnd else if (foundPackage) packageEnd else 0
    }

    companion object {
        fun getInstance(): SessionManager = ApplicationManager.getApplication().getService(SessionManager::class.java)
    }
}
