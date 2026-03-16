package com.redballoons.plugin.actions

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.ui.JBColor
import com.redballoons.plugin.model.SelectionContext
import com.redballoons.plugin.ops.OverRange
import com.redballoons.plugin.prompt.ContextData
import com.redballoons.plugin.prompt.Prompt
import com.redballoons.plugin.services.OpencodeService
import com.redballoons.plugin.services.SessionManager
import com.redballoons.plugin.ui.DiffInlayRenderer
import com.redballoons.plugin.ui.ProgressInlayRenderer
import com.redballoons.plugin.ui.PromptPopup
import java.awt.Color
import java.awt.Font

/**
 * Selection Mode Action (Ctrl+Shift+;)
 * Modifies only the selected code. If no selection, shows a warning.
 * Changes are applied in-memory (supports Undo).
 */
class SelectionModeAction : AnAction() {

    /**
     * Holds visual indicators while AI is processing.
     * Includes a RangeMarker that automatically tracks document changes.
     */
    private data class PendingIndicator(
        val highlighter: RangeHighlighter,
        val inlay: Inlay<*>,
        val renderer: ProgressInlayRenderer,
        val rangeMarker: RangeMarker,  // Tracks position even when document is edited
    )

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText

        if (selectedText.isNullOrBlank()) {
            Messages.showWarningDialog(
                project,
                "Please select some code first.\n\nSelection Mode only modifies the highlighted text.",
                "No Selection"
            )
            return
        }

        val document = editor.document
        val selectionStart = selectionModel.selectionStart
        val selectionEnd = selectionModel.selectionEnd

        // Get file path
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        val filePath = virtualFile?.path ?: "unknown"
        val fileName = virtualFile?.name ?: "unknown"

        // Get line numbers (1-indexed for display)
        val startLine = document.getLineNumber(selectionStart) + 1
        val endLine = document.getLineNumber(selectionEnd) + 1

        // Get full file content
        val fileContent = document.text

        val selectionContext = SelectionContext(
            filePath = filePath,
            fileName = fileName,
            startLine = startLine,
            endLine = endLine,
            selectedText = selectedText,
            fileContent = fileContent,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd
        )

        val popup = PromptPopup(
            project = project,
            mode = OpencodeService.ExecutionMode.SELECTION,
            editor = editor
        ) { userPrompt ->
            executeSelectionMode(e, selectionContext, userPrompt)
        }

        popup.show()
    }

    fun executeSelectionModeDirectly(
        project: Project,
        editor: Editor,
        selectionContext: SelectionContext,
        userPrompt: String,
    ) {
        Companion.executeSelectionModeDirectly(project, editor, selectionContext, userPrompt)
    }

    private fun executeSelectionMode(
        e: AnActionEvent,
        selectionContext: SelectionContext,
        userPrompt: String,
    ) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        Companion.executeSelectionModeDirectly(project, editor, selectionContext, userPrompt)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && e.project != null
    }

    companion object {
        fun executeSelectionModeDirectly(
            project: Project,
            editor: Editor,
            selectionContext: SelectionContext,
            userPrompt: String,
        ) {
            val context = Prompt.visual(project, selectionContext)
            context.userPrompt = userPrompt
            val sessionManager = SessionManager.getInstance()

            val rangeMarker = editor.document.createRangeMarker(selectionContext.selectionStart, selectionContext.selectionEnd).apply {
                isGreedyToLeft = false
                isGreedyToRight = false
            }
            val session = SessionManager.Session(context, editor, rangeMarker).apply {
                lastUserPrompt = userPrompt
                originalText = selectionContext.selectedText
                promptNumber = sessionManager.reservePromptNumber()
            }
            sessionManager.startSession(session)

            val action = SelectionModeAction()
            val indicator = action.addPendingIndicator(editor, session)
            session.highlighter = indicator.highlighter
            session.inlay = indicator.inlay
            session.renderer = indicator.renderer

            editor.selectionModel.removeSelection()
            OverRange(context) {
                ApplicationManager.getApplication().invokeLater {
                    if (!rangeMarker.isValid) {
                        action.removeVisualIndicators(editor, indicator)
                        sessionManager.removeSession(context.xid)
                        Messages.showWarningDialog(project, "The selection range is no longer valid.", "Range Invalid")
                        return@invokeLater
                    }

                    val visualData = context.data as ContextData.Visual
                    if (visualData.content.isNotBlank()) {
                        session.aiOutput = visualData.content
                        session.imports = visualData.imports

                        // Replace progress indicator with diff inlay
                        indicator.renderer.stopAnimation()
                        indicator.inlay.dispose()
                            session.renderer = null
                            replaceDiffInlay(
                                editor,
                                session,
                                rangeMarker,
                                session.originalText,
                                visualData.content
                            )
                    } else {
                        action.removeVisualIndicators(editor, indicator)
                        sessionManager.removeSession(context.xid)
                        Messages.showWarningDialog(project, "No output produced.", "No Output")
                    }
                }
            }
        }

        fun refineSession(
            project: Project,
            editor: Editor,
            session: SessionManager.Session,
            selectionContext: SelectionContext,
            userPrompt: String,
        ) {
            if (!session.rangeMarker.isValid) {
                Messages.showWarningDialog(project, "The selection range is no longer valid.", "Range Invalid")
                return
            }

            val sessionManager = SessionManager.getInstance()
            val context = Prompt.visual(project, selectionContext)
            context.userPrompt = userPrompt

            session.lastUserPrompt = userPrompt
            session.promptNumber = sessionManager.reservePromptNumber()

            if (session.rangeMarker.isValid) {
                session.originalText = editor.document.getText(
                    TextRange(session.rangeMarker.startOffset, session.rangeMarker.endOffset)
                )
            }

            val pendingRenderer = ProgressInlayRenderer(editor, "Implementing #${session.promptNumber}...")
            val pendingInlay = editor.inlayModel.addInlineElement(session.rangeMarker.startOffset, true, pendingRenderer)
            if (pendingInlay != null) {
                session.pendingRenderer?.stopAnimation()
                session.pendingInlay?.dispose()
                session.pendingRenderer = pendingRenderer
                session.pendingInlay = pendingInlay
                pendingRenderer.startAnimation(pendingInlay)
            }

            OverRange(context) {
                ApplicationManager.getApplication().invokeLater {
                    pendingRenderer.stopAnimation()
                    pendingInlay?.dispose()
                    if (session.pendingInlay == pendingInlay) {
                        session.pendingInlay = null
                        session.pendingRenderer = null
                    }

                    if (!sessionManager.isSessionActive(session)) return@invokeLater
                    if (!session.rangeMarker.isValid) {
                        Messages.showWarningDialog(project, "The selection range is no longer valid.", "Range Invalid")
                        return@invokeLater
                    }

                    val visualData = context.data as ContextData.Visual
                    if (visualData.content.isNotBlank()) {
                        session.aiOutput = visualData.content
                        session.imports = visualData.imports
                        replaceDiffInlay(
                            editor,
                            session,
                            session.rangeMarker,
                            session.originalText,
                            visualData.content
                        )
                    } else {
                        Messages.showWarningDialog(project, "No output produced for refinement.", "No Output")
                    }
                }
            }
        }

        private fun replaceDiffInlay(
            editor: Editor,
            session: SessionManager.Session,
            rangeMarker: RangeMarker,
            oldText: String,
            content: String,
        ) {
            session.inlay?.dispose()

            val endOffset = rangeMarker.endOffset
            val lineStartOffset = editor.document.getLineStartOffset(editor.document.getLineNumber(endOffset))
            val showAbove = endOffset == lineStartOffset

            val diffRenderer = DiffInlayRenderer(editor, oldText, content)
            session.inlay = editor.inlayModel.addBlockElement(
                endOffset,
                true,
                showAbove,
                0,
                diffRenderer
            )
        }
    }

    /**
     * Add visual indicators to mark the range being processed by AI:
     * - Background highlighter with subtle color
     * - Inline "Implementing..." badge with animated spinner
     * - RangeMarker to track position changes when document is edited
     */
    private fun addPendingIndicator(editor: Editor, session: SessionManager.Session): PendingIndicator {
        val startOffset = session.rangeMarker.startOffset
        val endOffset = session.rangeMarker.endOffset
        val markupModel = editor.markupModel

        val attributes = TextAttributes().apply {
            backgroundColor = JBColor(
                Color(255, 250, 205, 80),
                Color(100, 80, 0, 60)
            )
            fontType = Font.ITALIC
        }

        val highlighter = markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.SELECTION + 1,
            attributes,
            HighlighterTargetArea.EXACT_RANGE
        ).also {
            it.errorStripeTooltip = "AI is processing this code..."
        }

        val renderer = ProgressInlayRenderer(editor, "Implementing #${session.promptNumber}...")
        val inlay = editor.inlayModel.addInlineElement(startOffset, true, renderer)!!
        renderer.startAnimation(inlay)

        return PendingIndicator(highlighter, inlay, renderer, session.rangeMarker)
    }

    private fun removeVisualIndicators(editor: Editor, indicator: PendingIndicator) {
        ApplicationManager.getApplication().invokeLater {
            indicator.renderer.stopAnimation()
            indicator.inlay.dispose()
            editor.markupModel.removeHighlighter(indicator.highlighter)
        }
    }
}
