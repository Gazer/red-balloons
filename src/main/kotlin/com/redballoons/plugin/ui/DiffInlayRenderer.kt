package com.redballoons.plugin.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

class DiffInlayRenderer(
    private val editor: Editor,
    private val oldText: String,
    private val newText: String
) : EditorCustomElementRenderer {

    private enum class ChangeType {
        SAME,
        ADDED,
        REMOVED,
    }

    private data class DiffLine(
        val oldLine: String,
        val newLine: String,
        val oldType: ChangeType,
        val newType: ChangeType,
    )

    private val leftPadding = 8
    private val sectionSpacing = 22
    private val columnGap = 18
    private val actionRowHeight = 22
    private val headerRowHeight = 18
    private val actionRowPad = 8

    private var hoveredAction: String? = null
    private val actionRects = mutableMapOf<String, Rectangle>()

    fun setHoveredAction(action: String?) {
        if (hoveredAction != action) {
            hoveredAction = action
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
        return (buildDiffLines().size * editor.lineHeight) + headerRowHeight + actionRowHeight + 16
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val bgColor = JBColor(Color(250, 250, 250, 220), Color(40, 40, 40, 220))
        g2d.color = bgColor
        g2d.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)

        g2d.color = JBColor(Color(130, 130, 130, 120), Color(120, 120, 120, 120))
        g2d.drawRect(targetRegion.x, targetRegion.y, targetRegion.width - 1, targetRegion.height - 1)

        val lines = buildDiffLines()
        val lineCount = lines.size

        val lineFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val lineHeight = editor.lineHeight

        val removedColor = JBColor(Color(185, 0, 0), Color(255, 130, 130))
        val addedColor = JBColor(Color(0, 130, 0), Color(130, 255, 130))

        val columnWidth = (targetRegion.width - leftPadding * 2 - columnGap - sectionSpacing) / 2
        val leftColumnWidth = maxOf(columnWidth, 1)
        val rightColumnStart = targetRegion.x + leftPadding + leftColumnWidth + sectionSpacing + leftPadding
        val dividerX = targetRegion.x + leftPadding + leftColumnWidth + sectionSpacing / 2
        val markerWidth = 12

        g2d.font = lineFont
        g2d.color = editor.colorsScheme.defaultForeground
        val labelFont = lineFont.deriveFont(11f)
        g2d.font = labelFont
        val labelMetrics = g2d.fontMetrics
        g2d.drawString("Original", targetRegion.x + leftPadding, targetRegion.y + labelMetrics.ascent + 2)
        g2d.drawString("Suggested", rightColumnStart + 2, targetRegion.y + labelMetrics.ascent + 2)

        val contentStartY = targetRegion.y + headerRowHeight
        g2d.font = lineFont
        for (i in 0 until lineCount) {
            val y = contentStartY + labelMetrics.ascent + 2 + (i * lineHeight)
            val line = lines[i]

            val leftColor = when (line.oldType) {
                ChangeType.REMOVED -> removedColor
                ChangeType.ADDED -> addedColor
                ChangeType.SAME -> editor.colorsScheme.defaultForeground
            }
            val rightColor = when (line.newType) {
                ChangeType.ADDED -> addedColor
                ChangeType.REMOVED -> removedColor
                ChangeType.SAME -> editor.colorsScheme.defaultForeground
            }

            g2d.color = leftColor
            val leftMarker = when (line.oldType) {
                ChangeType.REMOVED -> "-"
                ChangeType.ADDED -> "+"
                else -> " "
            }
            g2d.drawString(leftMarker, targetRegion.x + leftPadding - markerWidth, y)
            val oldTextToShow = line.oldLine.takeUnless { it.length > 4000 } ?: ""
            g2d.drawString(oldTextToShow, targetRegion.x + leftPadding + 6, y)

            g2d.color = rightColor
            val rightMarker = when (line.newType) {
                ChangeType.ADDED -> "+"
                ChangeType.REMOVED -> "-"
                else -> " "
            }
            g2d.drawString(rightMarker, rightColumnStart - markerWidth + 2, y)
            val newTextToShow = line.newLine.takeUnless { it.length > 4000 } ?: ""
            g2d.drawString(newTextToShow, rightColumnStart + 6, y)
        }

        g2d.color = JBColor(Color(0, 0, 0, 40), Color(255, 255, 255, 80))
        g2d.drawLine(dividerX, targetRegion.y + 2, dividerX, targetRegion.y + targetRegion.height - actionRowHeight)

        val hintFont = editor.colorsScheme.getFont(EditorFontType.BOLD).deriveFont(11f)
        g2d.font = hintFont
        val hintFontMetrics = g2d.fontMetrics
        val actions = listOf("Yes", "No", "Refine")
        val hints = listOf("[Y]es", "[N]o", "[R]efine")

        var currentX = leftPadding
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

    private fun buildDiffLines(): List<DiffLine> {
        val oldLines = oldText.split("\n")
        val newLines = newText.split("\n")
        val m = oldLines.size
        val n = newLines.size

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val result = ArrayList<DiffLine>(m + n)
        var i = m
        var j = n
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
                result.add(DiffLine(oldLines[i - 1], newLines[j - 1], ChangeType.SAME, ChangeType.SAME))
                i--
                j--
            } else if (i > 0 && (j == 0 || dp[i - 1][j] >= dp[i][j - 1])) {
                result.add(DiffLine(oldLines[i - 1], "", ChangeType.REMOVED, ChangeType.SAME))
                i--
            } else if (j > 0) {
                result.add(DiffLine("", newLines[j - 1], ChangeType.SAME, ChangeType.ADDED))
                j--
            }
        }

        return result.asReversed()
    }
}
