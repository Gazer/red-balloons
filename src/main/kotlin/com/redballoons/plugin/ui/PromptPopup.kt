package com.redballoons.plugin.ui

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.redballoons.plugin.extensions.Completions
import com.redballoons.plugin.extensions.FilesProvider
import com.redballoons.plugin.model.AutoCompleteSuggestion
import com.redballoons.plugin.services.OpencodeService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

class PromptPopup(
    private val project: Project,
    private val mode: OpencodeService.ExecutionMode,
    private val editor: Editor?,
    private val onSubmit: (String) -> Unit,
) {
    companion object {
        private const val MIN_PREFIX_LENGTH = 3
    }

    private var popup: JBPopup? = null
    private lateinit var editorTextField: EditorTextField
    private var autoCompletePopup: JBPopup? = null
    private var autoCompleteList: JBList<AutoCompleteSuggestion>? = null
    private var autoCompleteListModel: DefaultListModel<AutoCompleteSuggestion>? = null
    private var currentMentionStart: Int = -1
    private val enterHandler = ShortcutSet {
        arrayOf(
            KeyboardShortcut(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                null
            )
        )
    }
    private val upArrowHandler = ShortcutSet {
        arrayOf(
            KeyboardShortcut(
                KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),
                null
            )
        )
    }
    private val downArrowHandler = ShortcutSet {
        arrayOf(
            KeyboardShortcut(
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
                null
            )
        )
    }
    private val escapeHandler = ShortcutSet {
        arrayOf(
            KeyboardShortcut(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                null
            )
        )
    }

    fun show() {
        Completions.setup(listOf(FilesProvider(project)))

        val panel = createPanel()

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, editorTextField)
            .setTitle(getModeTitle())
            .setMovable(true)
            .setResizable(false)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(false)
            .createPopup()

        if (editor != null) {
            val component = editor.contentComponent
            val visibleArea = editor.scrollingModel.visibleArea
            val point = Point(
                visibleArea.x + visibleArea.width / 2 - 200,
                visibleArea.y + visibleArea.height / 3
            )
            popup?.show(RelativePoint(component, point))
        } else {
            popup?.showCenteredInCurrentWindow(project)
        }

        ApplicationManager.getApplication().invokeLater {
            editorTextField.requestFocusInWindow()
            editorTextField.editor?.contentComponent?.requestFocusInWindow()
        }
    }

    private fun createPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(500, 170)
        panel.border = JBUI.Borders.empty(10)

        editorTextField = createEditorTextField()
        editorTextField.preferredSize = Dimension(480, 120)

        val scrollPane = JBScrollPane(editorTextField)
        scrollPane.preferredSize = Dimension(480, 120)
        scrollPane.border = JBUI.Borders.empty()

        panel.add(scrollPane, BorderLayout.CENTER)

        val legendLabel = JBLabel("ESC to close • ↵ (Enter) to send • ⇧↵ (Shift+Enter) new line • @ to mention files")
        legendLabel.font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        legendLabel.foreground = UIUtil.getContextHelpForeground()
        legendLabel.border = JBUI.Borders.emptyTop(5)
        panel.add(legendLabel, BorderLayout.SOUTH)

        return panel
    }

    private fun isAutoCompleteOpen(): Boolean {
        return autoCompletePopup?.let { !it.isDisposed && it.isVisible } ?: false
    }

    private fun confirmAutoCompleteSelection() {
        val list = autoCompleteList ?: return
        val selected = list.selectedValue ?: return
        insertFileReference(editorTextField, selected.relativePath, currentMentionStart)
    }

    private fun createEditorTextField(): EditorTextField {
        val document = EditorFactory.getInstance().createDocument("")

        val textField = object : EditorTextField(document, project, PlainTextFileType.INSTANCE, false, false) {
            override fun createEditor(): EditorEx {
                val editor = super.createEditor()

                editor.settings.apply {
                    isLineNumbersShown = false
                    isUseSoftWraps = true
                    isCaretRowShown = false
                }

                object : DumbAwareAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (isAutoCompleteOpen()) {
                            confirmAutoCompleteSelection()
                            return
                        }

                        val textToSend = text.trim()
                        if (textToSend.isNotEmpty()) {
                            ApplicationManager.getApplication().invokeLater {
                                popup?.closeOk(null)
                                onSubmit(textToSend)
                            }
                        }
                    }
                }.registerCustomShortcutSet(enterHandler, editor.contentComponent)

                object : DumbAwareAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (isAutoCompleteOpen()) {
                            dismissAutoComplete()
                        } else {
                            popup?.cancel()
                        }
                    }
                }.registerCustomShortcutSet(escapeHandler, editor.contentComponent)

                object : DumbAwareAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (isAutoCompleteOpen()) {
                            val list = autoCompleteList ?: return
                            val currentIndex = list.selectedIndex
                            if (currentIndex > 0) {
                                list.selectedIndex = currentIndex - 1
                                list.ensureIndexIsVisible(currentIndex - 1)
                            }
                        }
                    }
                }.registerCustomShortcutSet(upArrowHandler, editor.contentComponent)

                object : DumbAwareAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (isAutoCompleteOpen()) {
                            val list = autoCompleteList ?: return
                            val currentIndex = list.selectedIndex
                            if (currentIndex < list.model.size - 1) {
                                list.selectedIndex = currentIndex + 1
                                list.ensureIndexIsVisible(currentIndex + 1)
                            }
                        }
                    }
                }.registerCustomShortcutSet(downArrowHandler, editor.contentComponent)

                return editor
            }
        }

        textField.setOneLineMode(false)
        textField.border = JBUI.Borders.empty(4, 8)

        // Listener for triggers autocomplete
        textField.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val text = textField.text
                val caretOffset = textField.caretModel.offset
                val textBeforeCaret = text.take(caretOffset)
                var matchedTrigger: Char? = null
                var triggerStart = -1

                for (trigger in Completions.getTriggers()) {
                    val escapedTrigger = Regex.escape(trigger.toString())
                    val pattern = Regex("$escapedTrigger\\S*$")
                    val matchResult = pattern.find(textBeforeCaret)
                    if (matchResult != null) {
                        matchedTrigger = trigger
                        triggerStart = matchResult.range.first
                        break
                    }
                }

                if (matchedTrigger != null && triggerStart != -1) {
                    val prefix = textBeforeCaret.substring(triggerStart + 1)
                    if (prefix.length >= MIN_PREFIX_LENGTH) {
                        showSuggestionsAutoComplete(textField, matchedTrigger, prefix, triggerStart)
                    } else {
                        dismissAutoComplete()
                    }
                } else {
                    dismissAutoComplete()
                }
            }
        })

        return textField
    }

    private fun dismissAutoComplete() {
        autoCompletePopup?.cancel()
        autoCompletePopup = null
        autoCompleteList = null
        autoCompleteListModel = null
        currentMentionStart = -1
    }

    private fun showSuggestionsAutoComplete(
        textField: EditorTextField,
        matchedTrigger: Char,
        prefix: String,
        mentionStart: Int,
    ) {
        val suggestions = Completions.getSuggestions(matchedTrigger, prefix)

        if (suggestions.isEmpty()) {
            dismissAutoComplete()
            return
        }

        currentMentionStart = mentionStart

        // If popup is already open, just update its contents
        if (isAutoCompleteOpen() && autoCompleteListModel != null) {
            updateAutoCompleteList(suggestions)
            return
        }

        // Create a new popup with a JBList (no built-in filter)
        val listModel = DefaultListModel<AutoCompleteSuggestion>()
        suggestions.forEach { listModel.addElement(it) }

        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.selectedIndex = 0
        list.cellRenderer = object : ColoredListCellRenderer<AutoCompleteSuggestion>() {
            override fun customizeCellRenderer(
                jList: JList<out AutoCompleteSuggestion>,
                value: AutoCompleteSuggestion,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                icon = value.icon
                append(value.fileName)
                append("  ${value.relativePath}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        // Double-click to select
        list.addListSelectionListener { /* just visual selection update */ }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = list.selectedValue ?: return
                    insertFileReference(textField, selected.relativePath, currentMentionStart)
                }
            }
        })

        autoCompleteListModel = listModel
        autoCompleteList = list

        val scrollPane = JBScrollPane(list)
        scrollPane.preferredSize = Dimension(400, 200)
        scrollPane.border = JBUI.Borders.empty()

        val newPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, list)
            .setRequestFocus(false)
            .setFocusable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(false)
            .createPopup()

        dismissAutoComplete()
        autoCompletePopup = newPopup
        autoCompleteListModel = listModel
        autoCompleteList = list
        currentMentionStart = mentionStart

        val editorInstance = textField.editor ?: return
        val caretPos = editorInstance.caretModel.visualPosition
        val point = editorInstance.visualPositionToXY(caretPos)

        newPopup.show(
            RelativePoint(
                editorInstance.contentComponent,
                Point(point.x, point.y + editorInstance.lineHeight)
            )
        )
    }

    private fun updateAutoCompleteList(suggestions: List<AutoCompleteSuggestion>) {
        val listModel = autoCompleteListModel ?: return
        val list = autoCompleteList ?: return

        listModel.clear()
        suggestions.forEach { listModel.addElement(it) }

        if (listModel.size() > 0) {
            list.selectedIndex = 0
        }
    }

    private fun insertFileReference(textField: EditorTextField, filePath: String, mentionStart: Int) {
        val currentText = textField.text
        val caretOffset = textField.caretModel?.offset ?: currentText.length

        // Replace @prefix with @filepath
        val newText = StringBuilder(currentText)
            .replace(mentionStart, caretOffset, "@$filePath ")

        dismissAutoComplete()

        ApplicationManager.getApplication().runWriteAction {
            textField.document.setText(newText.toString())
            textField.caretModel?.moveToOffset(mentionStart + filePath.length + 2) // +2 for @ and space
        }
    }

    private fun getModeTitle(): String = when (mode) {
        OpencodeService.ExecutionMode.SELECTION -> "Selection Mode"
        OpencodeService.ExecutionMode.VIBE -> "Vibe Mode"
        OpencodeService.ExecutionMode.SEARCH -> "Search"
    }
}