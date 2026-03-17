package com.redballoons.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.redballoons.plugin.services.OpencodeService
import javax.swing.JComponent

class RedBalloonsSettingsConfigurable : Configurable {

    private var panel: DialogPanel? = null
    private val settings = RedBalloonsSettings.getInstance()

    private val cliPathField = JBTextField()
    private val modelField = ComboBox<String>()

    override fun getDisplayName(): String = "Red Balloons"

    override fun createComponent(): JComponent {
        // TODO: Should we do this in background?
        val models = OpencodeService.getInstance().getModels()
        models.forEach { modelField.addItem(it) }
        panel = panel {
            group("General") {
                row("Opencode CLI Path:") {
                    cell(cliPathField)
                        .columns(COLUMNS_LARGE)
                        .comment("Path to the opencode executable (e.g., /usr/local/bin/opencode or just 'opencode' if in PATH)")
                }
                row("Model:") {
                    cell(modelField)
                        .columns(COLUMNS_MEDIUM)
                        .comment("Model to use (leave empty for default). Examples: claude-3-5-sonnet, gemini-2.0-flash")
                }
            }
            group("Keyboard Shortcuts") {
                row {
                    comment(
                        """
                        <b>Selection Mode:</b> Command+Shift+; (modify selected code)<br>
                        <b>Vibe Mode:</b> Command+Shift+' (AI modifies project freely)<br>
                        <b>Search Mode:</b> Command+Shift+/ (AI-powered search)<br>
                        <b>Kill Switch:</b> Command+Shift+Escape (stop current process)<br>
                        <br>
                        <i>Tip: You can also access these from Tools → Opencode AI menu</i>
                    """.trimIndent()
                    )
                }
            }
        }
        return panel!!
    }

    override fun isModified(): Boolean {
        val selectedModel = modelField.selectedItem as? String ?: ""
        return cliPathField.text != settings.opencodeCliPath ||
                selectedModel != settings.modelName
    }

    override fun apply() {
        settings.opencodeCliPath = cliPathField.text
        settings.modelName = modelField.selectedItem as? String ?: ""
    }

    override fun reset() {
        cliPathField.text = settings.opencodeCliPath
        modelField.selectedItem = settings.modelName
    }

    override fun disposeUIResources() {
        panel = null
    }
}