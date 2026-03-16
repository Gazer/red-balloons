package com.redballoons.plugin.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.*

class DiffInlayRenderer(
    private val editor: Editor,
    private val oldText: String,
    private val newText: String
) : EditorCustomElementRenderer {

    private enum class ChangeType {
        SAME,
        CHANGED,
        ADDED,
        REMOVED,
    }

    private data class LinePair(
        val oldLine: String,
        val newLine: String,
        val type: ChangeType,
    )

    private val leftPadding = 8
    private val columnGap = 24
    private val sectionSpacing = 20
    private val actionRowHeight = 22
    private val headerRowHeight = 18
    private val actionRowPad = 8

    private var hoveredAction: String? = null
    private val actionRects = mutableMapOf<String, Rectangle>()

    fun setHoveredAction(action: String?) {
        if (hoveredAction != action) {
            hoveredAction = action
            // inlay.repaint() is needed, but we don't have the inlay here.
            // We'll rely on the caller to repaint.
        }
    }

    fun getActionAt(x: Int, y: Int, inlay: Inlay<*>): String? {
        val bounds = inlay.bounds ?: return null
        val localX = x - bounds.x
        val localY = y - bounds.y
        for ((action, rect) in actionRects) {
            if (rect.contains(localX, localY)) return action
        }
        return null
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        return editor.contentComponent.width
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val lines = maxOf(oldText.split("\n").size, newText.split("\n").size)
        return (lines * editor.lineHeight) + headerRowHeight + actionRowHeight + 16
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Background
        val bgColor = JBColor(Color(250, 250, 250, 220), Color(40, 40, 40, 220))
        g2d.color = bgColor
        g2d.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)

        // Border
        g2d.color = JBColor(Color(130, 130, 130, 120), Color(120, 120, 120, 120))
        g2d.drawRect(targetRegion.x, targetRegion.y, targetRegion.width - 1, targetRegion.height - 1)

        val pairs = buildDiffLines()
        val lineCount = pairs.size

        // Text
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g2d.font = font
        g2d.color = editor.colorsScheme.defaultForeground

        val lineHeight = editor.lineHeight

        val columnWidth = (targetRegion.width - leftPadding * 2 - columnGap - sectionSpacing) / 2
        val leftColumnWidth = maxOf(columnWidth, 1)
        val rightColumnStart = targetRegion.x + leftPadding + leftColumnWidth + sectionSpacing + leftPadding
        val dividerX = targetRegion.x + leftPadding + leftColumnWidth + sectionSpacing / 2

        val labelFont = font.deriveFont(11f)
        g2d.font = labelFont
        val labelMetrics = g2d.fontMetrics
        val leftLabel = "Original"
        val rightLabel = "Suggested"
        g2d.color = editor.colorsScheme.defaultForeground
        g2d.drawString(leftLabel, targetRegion.x + leftPadding, targetRegion.y + labelMetrics.ascent + 2)
        g2d.drawString(rightLabel, rightColumnStart + 2, targetRegion.y + labelMetrics.ascent + 2)

        val contentStartY = targetRegion.y + headerRowHeight
        g2d.font = font
        g2d.color = editor.colorsScheme.defaultForeground

        val lineFont = g2d.font
        val removedColor = JBColor(Color(185, 0, 0), Color(255, 130, 130))
        val addedColor = JBColor(Color(0, 130, 0), Color(130, 255, 130))

        for (i in 0 until lineCount) {
            val y = contentStartY + labelMetrics.ascent + 2 + (i * lineHeight)

            val pair = pairs[i]
            g2d.font = lineFont
            g2d.color = if (pair.type == ChangeType.REMOVED || pair.type == ChangeType.CHANGED) {
                removedColor
            } else {
                editor.colorsScheme.defaultForeground
            }
            g2d.drawString(pair.oldLine.takeUnless { it.length > 4000 } ?: "", targetRegion.x + leftPadding, y)

            g2d.color = if (pair.type == ChangeType.ADDED || pair.type == ChangeType.CHANGED) {
                addedColor
            } else {
                editor.colorsScheme.defaultForeground
            }
            g2d.drawString(pair.newLine.takeUnless { it.length > 4000 } ?: "", rightColumnStart + 2, y)
        }

        // Divider
        g2d.color = JBColor(Color(0, 0, 0, 40), Color(255, 255, 255, 80))
        g2d.drawLine(dividerX, targetRegion.y + 2, dividerX, targetRegion.y + targetRegion.height - actionRowHeight)

        // Action Hints
        val hintFont = editor.colorsScheme.getFont(EditorFontType.BOLD).deriveFont(11f)
        g2d.font = hintFont
        val hintFontMetrics = g2d.fontMetrics
        
        val actions = listOf("Yes", "No", "Refine")
        val isMac = System.getProperty("os.name").contains("Mac", ignoreCase = true)
        val cmdKey = if (isMac) "Cmd" else "Ctrl"
        val hints = listOf("[Y]es ($cmdKey+Y)", "[N]o ($cmdKey+N)", "[R]efine ($cmdKey+R)")
        
        var currentX = 8
        val hintY = targetRegion.height - actionRowPad - (hintFontMetrics.ascent / 2)
        
        actionRects.clear()
        
        for (i in actions.indices) {
            val action = actions[i]
            val hint = hints[i]
            val width = hintFontMetrics.stringWidth(hint)
            val rect = Rectangle(currentX - 2, hintY - hintFontMetrics.ascent - 2, width + 4, hintFontMetrics.height + 4)
            actionRects[action] = rect
            
            if (hoveredAction == action) {
                g2d.color = JBColor(Color(0, 0, 0, 30), Color(255, 255, 255, 30))
                g2d.fillRoundRect(targetRegion.x + rect.x, targetRegion.y + rect.y, rect.width, rect.height, 4, 4)
            }
            
            g2d.color = if (hoveredAction == action) JBColor.BLUE else JBColor(Color(0, 100, 0), Color(150, 255, 150))
            g2d.drawString(hint, targetRegion.x + currentX, targetRegion.y + hintY)
            
            currentX += width + 20
        }
        
        g2d.dispose()
    }

    private fun buildDiffLines(): List<LinePair> {
        val oldLines = oldText.split("\n")
        val newLines = newText.split("\n")
        val maxLines = maxOf(oldLines.size, newLines.size)
        return (0 until maxLines).map { index ->
            val oldLine = oldLines.getOrNull(index) ?: ""
            val newLine = newLines.getOrNull(index) ?: ""

            val type = when {
                oldLine.isEmpty() && newLine.isNotEmpty() && index >= oldLines.size -> ChangeType.ADDED
                oldLine.isNotEmpty() && newLine.isEmpty() && index >= newLines.size -> ChangeType.REMOVED
                oldLine == newLine -> ChangeType.SAME
                oldLine.isEmpty() && newLine.isEmpty() -> ChangeType.SAME
                else -> ChangeType.CHANGED
            }

            LinePair(oldLine, newLine, type)
        }
    }
}
