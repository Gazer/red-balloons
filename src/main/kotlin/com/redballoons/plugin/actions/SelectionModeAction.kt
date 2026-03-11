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
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.ui.JBColor
import com.redballoons.plugin.ops.OverRange
import com.redballoons.plugin.prompt.ContextData
import com.redballoons.plugin.prompt.Prompt
import com.redballoons.plugin.services.OpencodeService
import com.redballoons.plugin.services.SelectionContext
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

    private fun executeSelectionMode(
        e: AnActionEvent,
        selectionContext: SelectionContext,
        userPrompt: String,
    ) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val context = Prompt.visual(project, selectionContext)

        context.userPrompt = userPrompt

        val indicator = addPendingIndicator(
            editor,
            selectionContext.selectionStart,
            selectionContext.selectionEnd,
        )
        editor.selectionModel.removeSelection()
        OverRange(context) {
            removeVisualIndicators(editor, indicator)

            val visualData = context.data as ContextData.Visual
            if (visualData.content.isNotBlank()) {
                val rangeValid = indicator.rangeMarker.isValid
                if (!rangeValid) {
                    indicator.rangeMarker.dispose()
                    Messages.showWarningDialog(
                        project,
                        "The selection range is no longer valid.\nThe document may have changed too much.",
                        "Range Invalid"
                    )
                    return@OverRange
                }

                // Apply changes in-memory using WriteCommandAction (enables Undo)
                WriteCommandAction.runWriteCommandAction(project, "Opencode: Selection Mode", null, {
                    // First, add any new imports at the top of the file
                    if (visualData.imports.isNotEmpty()) {
                        val importsText = visualData.imports.joinToString("\n") + "\n"

                        // Find where to insert imports (after package declaration, before first non-import)
                        val insertPosition = findImportInsertPosition(document.text)
                        document.insertString(insertPosition, importsText)
                    }

                    // Replace the selection with new content using tracked positions
                    // RangeMarker auto-adjusts for any prior document changes (like import insertion)
                    val currentStart = indicator.rangeMarker.startOffset
                    val currentEnd = indicator.rangeMarker.endOffset
                    document.replaceString(currentStart, currentEnd, visualData.content)
                    // Now dispose the rangeMarker since we're done
                    indicator.rangeMarker.dispose()

                    // Commit document changes and run Optimize Imports to clean up duplicates and order
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
            } else {
                indicator.rangeMarker.dispose()
                Messages.showWarningDialog(
                    project,
                    "Opencode completed but produced no output.\nThe temp file may not have been written.",
                    "No Output"
                )
            }
        }
    }

    /**
     * Find the position to insert new imports.
     * Returns the position after the last existing import, or after package declaration.
     */
    private fun findImportInsertPosition(fileContent: String): Int {
        val lines = fileContent.lines()
        var lastImportEnd = 0
        var currentPos = 0
        var foundPackage = false
        var packageEnd = 0

        for (line in lines) {
            val trimmed = line.trim()

            // TODO I dont like this approach, does IDE have something to help?
            if (trimmed.startsWith("package ")) {
                foundPackage = true
                packageEnd = currentPos + line.length + 1 // +1 for newline
            } else if (trimmed.startsWith("import ")) {
                lastImportEnd = currentPos + line.length + 1
            } else if (
                trimmed.isNotEmpty() &&
                !trimmed.startsWith("//") &&
                !trimmed.startsWith("/*") &&
                !trimmed.startsWith("*")
            ) {
                // Found first non-import, non-comment line
                if (lastImportEnd > 0) {
                    return lastImportEnd
                } else if (foundPackage) {
                    return packageEnd
                }
                break
            }

            currentPos += line.length + 1
        }

        return if (lastImportEnd > 0) lastImportEnd else if (foundPackage) packageEnd else 0
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && e.project != null
    }

    /**
     * Add visual indicators to mark the range being processed by AI:
     * - Background highlighter with subtle color
     * - Inline "Implementing..." badge with animated spinner
     * - RangeMarker to track position changes when document is edited
     */
    private fun addPendingIndicator(editor: Editor, startOffset: Int, endOffset: Int): PendingIndicator {
        val document = editor.document
        val markupModel = editor.markupModel

        // Create a RangeMarker that survives document edits
        // This will automatically adjust startOffset/endOffset as the document changes
        val rangeMarker = document.createRangeMarker(startOffset, endOffset).apply {
            isGreedyToLeft = false
            isGreedyToRight = false
        }

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

        val renderer = ProgressInlayRenderer(editor)
        val inlay = editor.inlayModel.addInlineElement(startOffset, true, renderer)!!
        renderer.startAnimation(inlay)

        return PendingIndicator(highlighter, inlay, renderer, rangeMarker)
    }

    private fun removeVisualIndicators(editor: Editor, indicator: PendingIndicator) {
        ApplicationManager.getApplication().invokeLater {
            indicator.renderer.stopAnimation()
            indicator.inlay.dispose()
            editor.markupModel.removeHighlighter(indicator.highlighter)
        }
    }
}