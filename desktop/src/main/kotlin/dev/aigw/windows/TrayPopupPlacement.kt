package dev.aigw.windows

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

internal object TrayPopupPlacement {
    /** All arguments are AWT logical screen coordinates, including on monitors left of the primary. */
    fun position(anchor: Point, size: Dimension, usable: Rectangle): Point {
        val gap = 8
        val above = anchor.y - size.height - gap
        val y = if (above >= usable.y) above else anchor.y + gap
        return Point(
            (anchor.x - size.width + 12).coerceIn(usable.x, maxOf(usable.x, usable.x + usable.width - size.width)),
            y.coerceIn(usable.y, maxOf(usable.y, usable.y + usable.height - size.height)),
        )
    }
}
