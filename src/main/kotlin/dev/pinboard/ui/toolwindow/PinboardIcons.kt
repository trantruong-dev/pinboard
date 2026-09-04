package dev.pinboard.ui.toolwindow

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Plugin icons. The `_dark` variant is picked up automatically by [IconLoader]. */
object PinboardIcons {

  @JvmField
  val ToolWindow: Icon =
    IconLoader.getIcon("/icons/pinboardToolWindow.svg", PinboardIcons::class.java)
}
