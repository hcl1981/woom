package app.woom

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * A tiny "Woom is starting…" window shown immediately at launch, using plain Swing so it appears
 * long before Compose and its graphics library have finished loading. On a fresh Windows machine the
 * first start is slow (the virus scanner checks the unsigned program and the graphics library is
 * unpacked once); without this the user sees nothing and may start the program again and again.
 */
object Splash {
    private var window: JWindow? = null

    fun show() {
        SwingUtilities.invokeLater {
            runCatching {
                val w = JWindow()
                val panel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = Color(0x2B, 0x2B, 0x2B)
                    border = BorderFactory.createEmptyBorder(22, 36, 22, 36)
                }
                icon()?.let {
                    panel.add(JLabel(ImageIcon(it.getScaledInstance(64, 64, java.awt.Image.SCALE_SMOOTH))).apply {
                        alignmentX = JLabel.CENTER_ALIGNMENT
                    })
                }
                panel.add(JLabel("Woom startet…", SwingConstants.CENTER).apply {
                    foreground = Color(0xF2, 0xE8, 0xDC)
                    font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
                    border = BorderFactory.createEmptyBorder(14, 0, 0, 0)
                    alignmentX = JLabel.CENTER_ALIGNMENT
                })
                w.contentPane.add(panel)
                w.pack()
                w.size = Dimension(maxOf(w.width, 220), w.height)
                w.setLocationRelativeTo(null)
                w.isAlwaysOnTop = true
                w.isVisible = true
                window = w
            }
        }
    }

    fun hide() {
        SwingUtilities.invokeLater {
            window?.let { it.isVisible = false; it.dispose() }
            window = null
        }
    }

    private fun icon(): BufferedImage? =
        runCatching { ImageIO.read(Splash::class.java.getResource("/woom.png")) }.getOrNull()
}
