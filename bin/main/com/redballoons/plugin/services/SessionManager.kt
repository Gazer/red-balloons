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
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicInteger
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
    private val promptCounter = AtomicInteger(0)
    private var hoveredInlay: Inlay<*>? = null

    fun getSessionForShortcut(editor: Editor): Session? {
        val activeSession = getActiveReviewSession(editor)
        if (activeSession != null) return activeSession

        val sessionAtCaret = findSessionAtCaret(editor)
        if (sessionAtCaret != null) return sessionAtCaret

        val last = lastReviewedSession.get()
        if (last != null) {
            val session = getSession(last)
            if (session?.editor == editor) {
                return session
            }
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
        if (inlay == null) return
        val session = findSessionByInlay(inlay) ?: return
        setActiveReviewSession(session)
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

    fun reservePromptNumber(): Int = promptCounter.incrementAndGet()

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

        // Priority 2: session nearest caret line (range and inlay-aware)
        val caretLine = runCatching { document.getLineNumber(offset) }.getOrElse { -1 }
        if (caretLine != -1) {
            val nearestByLine = sessionsInEditor
                .map { session -> session to lineDistanceToSession(document, caretLine, session) }
                .minByOrNull { it.second }
            if (nearestByLine != null && nearestByLine.second <= 8) return nearestByLine.first
        }

        // Priority 3: inlay block proximity for keyboard-only navigation (IdeaVim-friendly)
        val caretPoint = editor.visualPositionToXY(editor.caretModel.visualPosition)
        val nearestByInlay = sessionsInEditor
            .mapNotNull { session ->
                val bounds = session.inlay?.bounds ?: return@mapNotNull null
                session to distanceToRect(caretPoint, bounds)
            }
            .minByOrNull { it.second }
        if (nearestByInlay != null && nearestByInlay.second <= editor.lineHeight * 4) {
            return nearestByInlay.first
        }

        return null
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
        val activeReviewSession = getActiveReviewSession(editor)
        if (activeReviewSession != null) {
            setActiveReviewSession(activeReviewSession)
            return activeReviewSession
        }

        val sessionAtCaret = findSessionAtCaret(editor)
        if (sessionAtCaret != null) {
            setActiveReviewSession(sessionAtCaret)
            return sessionAtCaret
        }
        
        val hoveredInlay = getHoveredInlay()
        if (hoveredInlay != null) {
            val sessionByHover = findSessionByInlay(hoveredInlay)
            if (sessionByHover?.editor == editor && sessionByHover.aiOutput != null) {
                setActiveReviewSession(sessionByHover)
                return sessionByHover
            }
        }

        val sessionsWithOutput = sessions.values.filter { it.editor == editor && it.rangeMarker.isValid && it.aiOutput != null }
        if (sessionsWithOutput.size == 1) {
            setActiveReviewSession(sessionsWithOutput.first())
            return sessionsWithOutput.first()
        }

        if (sessionsWithOutput.isNotEmpty()) {
            val caretLine = runCatching { documentLineAtCaret(editor, editor.caretModel.offset) }.getOrNull() ?: return null
            val best = sessionsWithOutput
                .minByOrNull { lineDistanceToSession(editor.document, caretLine, it) }
            if (best != null) setActiveReviewSession(best)
            return best
        }

        return null
    }

    private fun documentLineAtCaret(editor: Editor, offset: Int): Int {
        return runCatching { editor.document.getLineNumber(offset) }.getOrElse { 0 }
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
        }
    }

    private fun lineDistanceToSession(
        document: com.intellij.openapi.editor.Document,
        caretLine: Int,
        session: Session,
    ): Int {
        val startLine = document.getLineNumber(session.rangeMarker.startOffset)
        val endLine = document.getLineNumber(session.rangeMarker.endOffset)
        val distanceToRange = when {
            caretLine < startLine -> startLine - caretLine
            caretLine > endLine -> caretLine - endLine
            else -> 0
        }

        val inlay = session.inlay ?: return distanceToRange
        val inlayOffset = inlay.offset.coerceIn(0, document.textLength)
        val inlayLine = document.getLineNumber(inlayOffset)
        val distanceToInlayAnchor = kotlin.math.abs(caretLine - inlayLine)

        return minOf(distanceToRange, distanceToInlayAnchor)
    }

    private fun distanceToRect(point: Point, rect: Rectangle): Int {
        val dx = when {
            point.x < rect.x -> rect.x - point.x
            point.x > rect.x + rect.width -> point.x - (rect.x + rect.width)
            else -> 0
        }
        val dy = when {
            point.y < rect.y -> rect.y - point.y
            point.y > rect.y + rect.height -> point.y - (rect.y + rect.height)
            else -> 0
        }
        return kotlin.math.max(dx, dy)
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
