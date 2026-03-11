package com.redballoons.plugin.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.Timer

/**
 * Custom inlay renderer that displays an animated "Implementing..." indicator
 * with a spinning circle to show AI is processing.
 */
class ProgressInlayRenderer(
    private val editor: Editor,
    private val message: String = "Implementing...",
) : EditorCustomElementRenderer {

    private var animationFrame = 0
    private val spinnerFrames = arrayOf("◐", "◓", "◑", "◒")
    private var timer: Timer? = null
    private var inlay: Inlay<*>? = null
    private val bgColor = JBColor(
        Color(255, 193, 7, 200),
        Color(255, 160, 0, 180),
    )

    fun startAnimation(inlay: Inlay<*>) {
        this.inlay = inlay
        timer = Timer(150) {
            animationFrame = (animationFrame + 1) % spinnerFrames.size
            inlay.repaint()
        }
        timer?.start()
    }

    fun stopAnimation() {
        timer?.stop()
        timer = null
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fontMetrics = editor.contentComponent.getFontMetrics(getFont())
        val text = "${spinnerFrames[animationFrame]} $message"
        return fontMetrics.stringWidth(text) + 16 // padding
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return editor.lineHeight
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Draw background pill
        g2d.color = bgColor
        g2d.fillRoundRect(
            targetRegion.x + 2,
            targetRegion.y + 2,
            targetRegion.width - 4,
            targetRegion.height - 4,
            8, 8
        )

        // Draw text
        val textColor = JBColor(
            Gray._0,
            Gray._255
        )
        g2d.color = textColor
        g2d.font = getFont()

        val text = "${spinnerFrames[animationFrame]} $message"
        val fontMetrics = g2d.fontMetrics
        val textY = targetRegion.y + ((targetRegion.height + fontMetrics.ascent - fontMetrics.descent) / 2)
        g2d.drawString(text, targetRegion.x + 8, textY)

        g2d.dispose()
    }

    private fun getFont(): Font {
        return editor.colorsScheme.getFont(EditorFontType.BOLD).deriveFont(11f)
    }
}