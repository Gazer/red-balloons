package com.redballoons.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.redballoons.plugin.services.OpencodeService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JPanel

class PromptPopup(
    private val project: Project,
    private val mode: OpencodeService.ExecutionMode,
    private val editor: Editor?,
    private val initialText: String = "",
    private val onSubmit: (String) -> Unit,
) {
    private var popup: JBPopup? = null
    private val promptArea = JBTextArea(6, 40)

    fun show() {
        if (initialText.isNotEmpty()) {
            promptArea.text = initialText
            promptArea.selectAll()
        }
        val panel = createPanel()

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, promptArea)
            .setTitle(getModeTitle())
            .setMovable(true)
            .setResizable(false)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(true)
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
            promptArea.requestFocusInWindow()
        }
    }


    private fun createPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(500, 150)
        panel.border = JBUI.Borders.empty(10)

        promptArea.lineWrap = true
        promptArea.wrapStyleWord = true
        promptArea.border = JBUI.Borders.empty(4, 8)
        promptArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        if (e.isShiftDown) {
                            // Allow Shift+Enter for new lines
                            return
                        }
                        val text = promptArea.text.trim()
                        if (text.isNotEmpty()) {
                            popup?.closeOk(null)
                            onSubmit(text)
                        }
                        e.consume()
                    }

                    KeyEvent.VK_ESCAPE -> {
                        popup?.cancel()
                    }
                }
            }
        })

        val scrollPane = JBScrollPane(promptArea)
        scrollPane.preferredSize = Dimension(480, 120)

        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun getModeTitle(): String = when (mode) {
        OpencodeService.ExecutionMode.SELECTION -> "Selection Mode"
        OpencodeService.ExecutionMode.VIBE -> "Vibe Mode"
        OpencodeService.ExecutionMode.SEARCH -> "Search"
    }
}