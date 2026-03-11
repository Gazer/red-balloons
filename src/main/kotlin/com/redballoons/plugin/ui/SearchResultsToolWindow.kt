package com.redballoons.plugin.ui

import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.Timer

class SearchResultsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SearchResultsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

/**
 * Search result with full location info:
 * - filePath: absolute path to file
 * - lineNumber: 1-based line number
 * - columnNumber: 1-based column number
 * - highlightLines: number of lines to highlight
 * - notes: description of why this result is relevant
 */

class SearchResultsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<SearchResult>()
    private val resultsList = JBList(listModel)
    private val headerLabel = JBLabel("Search Results")
    private val countLabel = JBLabel("")

    init {
        // Header
        val headerPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(headerLabel, BorderLayout.WEST)
            add(countLabel, BorderLayout.EAST)
        }
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD)

        add(headerPanel, BorderLayout.NORTH)

        // Results list with custom renderer
        resultsList.cellRenderer = SearchResultRenderer()
        resultsList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        add(JBScrollPane(resultsList), BorderLayout.CENTER)

        // Double-click to navigate
        resultsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = resultsList.selectedValue
                    if (selected != null) {
                        navigateToResult(selected)
                    }
                }
            }
        })

        // Enter key to navigate
        resultsList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    val selected = resultsList.selectedValue
                    if (selected != null) {
                        navigateToResult(selected)
                    }
                }
            }
        })
    }

    fun setResults(results: List<SearchResult>, query: String = "") {
        listModel.clear()
        results.forEach { listModel.addElement(it) }

        countLabel.text = "${results.size} result${if (results.size != 1) "s" else ""}"
        if (query.isNotBlank()) {
            headerLabel.text = "Search: $query"
        }

        // Select first result if available
        if (results.isNotEmpty()) {
            resultsList.selectedIndex = 0
        }
    }

    fun clearResults() {
        listModel.clear()
        countLabel.text = ""
        headerLabel.text = "Search Results"
    }

    private fun navigateToResult(result: SearchResult) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(result.filePath)

        if (virtualFile != null) {
            // Navigate to the file and position
            val descriptor = OpenFileDescriptor(
                project,
                virtualFile,
                result.lineNumber - 1,  // Convert to 0-indexed
                result.columnNumber - 1  // Convert to 0-indexed
            )

            val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)

            // Add highlight for the relevant lines
            editor?.let { ed ->
                val document = ed.document
                val startLine = result.lineNumber - 1  // 0-indexed
                val endLine = minOf(startLine + result.highlightLines, document.lineCount) - 1

                if (startLine < document.lineCount) {
                    val startOffset = document.getLineStartOffset(startLine)
                    val endOffset = if (endLine < document.lineCount) {
                        document.getLineEndOffset(endLine)
                    } else {
                        document.textLength
                    }

                    // Add temporary highlight
                    val attributes = TextAttributes().apply {
                        backgroundColor = JBColor(
                            Color(255, 255, 0, 60),  // Light: yellow
                            Color(128, 128, 0, 60)   // Dark: olive
                        )
                    }

                    val highlighter = ed.markupModel.addRangeHighlighter(
                        startOffset,
                        endOffset,
                        HighlighterLayer.SELECTION - 1,
                        attributes,
                        HighlighterTargetArea.EXACT_RANGE
                    )

                    // Remove highlight after 3 seconds
                    Timer(3000) {
                        ed.markupModel.removeHighlighter(highlighter)
                    }.apply {
                        isRepeats = false
                        start()
                    }

                    // Scroll to show the highlighted area
                    ed.scrollingModel.scrollToCaret(ScrollType.CENTER)
                }
            }
        }
    }

    /**
     * Custom renderer for search results
     */
    private inner class SearchResultRenderer : ListCellRenderer<SearchResult> {
        private val panel = JPanel(BorderLayout())
        private val topLine = JBLabel()
        private val bottomLine = JBLabel()

        init {
            panel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)

            val textPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(topLine)
                add(bottomLine)
            }

            panel.add(textPanel, BorderLayout.CENTER)

            topLine.font = topLine.font.deriveFont(Font.BOLD)
            bottomLine.foreground = JBColor.GRAY
        }

        override fun getListCellRendererComponent(
            list: JList<out SearchResult>,
            value: SearchResult,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            // Top line: filename:line:col (highlight X lines)
            val linesInfo = if (value.highlightLines > 1) {
                " (${value.highlightLines} lines)"
            } else {
                ""
            }
            topLine.text = "${value.fileName}:${value.lineNumber}:${value.columnNumber}$linesInfo"

            // Bottom line: notes and path
            bottomLine.text = "${value.notes} - ${value.displayPath}"

            // Selection colors
            if (isSelected) {
                panel.background = list.selectionBackground
                topLine.foreground = list.selectionForeground
                bottomLine.foreground = list.selectionForeground
            } else {
                panel.background = list.background
                topLine.foreground = list.foreground
                bottomLine.foreground = JBColor.GRAY
            }

            panel.isOpaque = true
            return panel
        }
    }
}