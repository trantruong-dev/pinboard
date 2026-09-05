package dev.pinboard.ui.toolwindow

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Plugin icons. The `_dark` variant is picked up automatically by [IconLoader]. */
object PinboardIcons {

  @JvmField
  val ToolWindow: Icon =
    IconLoader.getIcon("/icons/pinboardToolWindow.svg", PinboardIcons::class.java)

  /** Marks a pinned line in the editor gutter. Sized 12x12, as the gutter expects. */
  @JvmField
  val Pin: Icon =
    IconLoader.getIcon("/icons/pinGutter.svg", PinboardIcons::class.java)
}
