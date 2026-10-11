package dev.aigw.windows

import java.awt.*
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.ImageIcon

/** One embedded artwork shared by the EXE, frame, banner, tray and tray flyout. */
internal object OceanIcon {
    private val original: BufferedImage by lazy {
        requireNotNull(OceanIcon::class.java.getResourceAsStream("/branding/ocean-icon.png")) { "Ocean icon resource missing" }.use { ImageIO.read(it) }
    }
    private val images = mutableMapOf<Int, BufferedImage>()
    @Synchronized fun image(size: Int): BufferedImage = images.getOrPut(size) {
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.drawImage(original, 0, 0, size, size, null)
            } finally { g.dispose() }
        }
    }
    fun windowImages(): List<Image> = listOf(16, 24, 32, 48, 64, 128, 256).map { image(it) }
    fun trayImage(): Image = BaseMultiResolutionImage(*listOf(16, 20, 24, 32, 40, 48, 64).map { image(it) }.toTypedArray())
    fun swing(size: Int) = ImageIcon(image(size))
}
