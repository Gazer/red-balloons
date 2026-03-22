package com.redballoons.plugin.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.redballoons.plugin.services.OpencodeService
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.table.DefaultTableModel

class RedBalloonsSettingsConfigurable : Configurable {

    private var panel: DialogPanel? = null
    private val settings = RedBalloonsSettings.getInstance()

    private val cliPathField = JBTextField()
    private val modelField = ComboBox<String>()
    private val refreshModelsButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh models list"
        isEnabled = true
        addActionListener {
            loadModels(refresh = true)
        }
    }

    // Extension Files Provider fields
    private val extensionFilesProviderEnabledCheckbox = JBCheckBox("Enable Extension Files Provider")
    private val extensionFilesProviderMaxFilesSpinner = JSpinner(SpinnerNumberModel(5000, 100, 100000, 100))
    private val excludePatternsTableModel = DefaultTableModel(arrayOf("Pattern"), 0)
    private val excludePatternsTable = JBTable(excludePatternsTableModel).apply {
        autoResizeMode = JBTable.AUTO_RESIZE_ALL_COLUMNS
    }
    private val excludePatternsPanel = ToolbarDecorator.createDecorator(excludePatternsTable)
        .setAddAction {
            excludePatternsTableModel.addRow(arrayOf(""))
            val newRow = excludePatternsTableModel.rowCount - 1
            excludePatternsTable.editCellAt(newRow, 0)
            excludePatternsTable.requestFocus()
        }
        .setRemoveAction {
            val selectedRow = excludePatternsTable.selectedRow
            if (selectedRow >= 0) {
                excludePatternsTableModel.removeRow(selectedRow)
            }
        }
        .disableUpDownActions()
        .createPanel().apply {
            preferredSize = Dimension(400, 150)
            minimumSize = Dimension(200, 100)
        }

    override fun getDisplayName(): String = "Red Balloons"

    override fun createComponent(): JComponent {
        panel = panel {
            group("General") {
                row("Opencode CLI Path:") {
                    cell(cliPathField)
                        .columns(COLUMNS_LARGE)
                        .comment("Path to the opencode executable")
                }
                row("Model:") {
                    cell(modelField)
                        .columns(COLUMNS_MEDIUM)
                    cell(refreshModelsButton)
                        .comment("Model to use (leave empty for default)")
                }
            }
            group("Extension Files Provider") {
                row {
                    cell(extensionFilesProviderEnabledCheckbox)
                        .comment("When enabled, provides file listing to opencode for context")
                }
                row("Max Files:") {
                    cell(extensionFilesProviderMaxFilesSpinner)
                        .comment("Maximum number of files to include (100-100000)")
                }
                row("Exclude Patterns:") {
                    cell(excludePatternsPanel)
                        .align(Align.FILL)
                        .comment("Glob patterns to exclude (e.g., *.log, node_modules)")
                }.resizableRow()
            }
            group("Keyboard Shortcuts") {
                row {
                    comment(
                        """
                        <b>Selection Mode:</b> Command+Shift+; (modify selected code)<br>
                        <b>Vibe Mode:</b> Command+Shift+' (AI modifies project freely)<br>
                        <b>Search Mode:</b> Command+Shift+/ (AI-powered search)<br>
                        <b>Kill Switch:</b> Command+Shift+Escape (stop current process)
                    """.trimIndent()
                    )
                }
            }
        }
        return panel!!
    }

    override fun isModified(): Boolean {
        val selectedModel = modelField.selectedItem as? String ?: ""
        val currentExcludePatterns = getExcludePatternsFromTable()
        return cliPathField.text != settings.opencodeCliPath ||
                selectedModel != settings.modelName ||
                extensionFilesProviderEnabledCheckbox.isSelected != settings.extensionFilesProviderEnabled ||
                (extensionFilesProviderMaxFilesSpinner.value as Int) != settings.extensionFilesProviderMaxFiles ||
                currentExcludePatterns != settings.extensionFilesProviderExcludePatterns
    }

    override fun apply() {
        settings.opencodeCliPath = cliPathField.text
        settings.modelName = modelField.selectedItem as? String ?: ""
        settings.extensionFilesProviderEnabled = extensionFilesProviderEnabledCheckbox.isSelected
        settings.extensionFilesProviderMaxFiles = extensionFilesProviderMaxFilesSpinner.value as Int
        settings.extensionFilesProviderExcludePatterns = getExcludePatternsFromTable().toMutableList()
    }

    override fun reset() {
        cliPathField.text = settings.opencodeCliPath
        modelField.selectedItem = settings.modelName
        extensionFilesProviderEnabledCheckbox.isSelected = settings.extensionFilesProviderEnabled
        extensionFilesProviderMaxFilesSpinner.value = settings.extensionFilesProviderMaxFiles
        excludePatternsTableModel.rowCount = 0
        settings.extensionFilesProviderExcludePatterns.forEach { pattern ->
            excludePatternsTableModel.addRow(arrayOf(pattern))
        }
        loadModels(false)
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun loadModels(refresh: Boolean = false) {
        refreshModelsButton.isEnabled = false
        val currentSelection = modelField.selectedItem as? String
        ApplicationManager.getApplication().executeOnPooledThread {
            val models = OpencodeService.getInstance().getModels(refresh)
            ApplicationManager.getApplication().invokeLater {
                modelField.removeAllItems()
                models.forEach { modelField.addItem(it) }

                if (currentSelection != null && models.contains(currentSelection)) {
                    modelField.selectedItem = currentSelection
                } else if (settings.modelName.isNotEmpty() && models.contains(settings.modelName)) {
                    modelField.selectedItem = settings.modelName
                }
                refreshModelsButton.isEnabled = true
            }
        }
    }

    private fun getExcludePatternsFromTable(): List<String> {
        val patterns = mutableListOf<String>()
        for (i in 0 until excludePatternsTableModel.rowCount) {
            val pattern = (excludePatternsTableModel.getValueAt(i, 0) as? String)?.trim() ?: ""
            if (pattern.isNotEmpty()) {
                patterns.add(pattern)
            }
        }
        return patterns
    }
}