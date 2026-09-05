package dev.pinboard.ui.theme

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil

/**
 * Role-named colour tokens. Every colour in the plugin's UI dereferences a member here rather than
 * naming a hex on the spot, so a status can be recoloured in one place and the whole panel follows.
 *
 * Every token is a [JBColor], never a plain `Color`. That is the part that matters: a Swing property
 * holds whatever object it was assigned, so a plain colour handed to `component.foreground` freezes
 * at the theme active when it was assigned and keeps painting that theme after the user switches. A
 * [JBColor] re-resolves on every paint, so assigning one once is enough. The getters are computed
 * for the same reason one level up - a stored `val` would resolve at class-load time.
 *
 * Translucency is a function of a token ([soft]), never a new token: there is one amber in the
 * plugin, not an amber and a pale amber that can drift apart.
 */
object PinboardColors {

  /** Tool window background, and the ground its rows are painted on. */
  val surface: JBColor get() = JBColor.lazy { UIUtil.getPanelBackground() }

  /** Secondary text: timestamps, counts, the footer's status line. */
  val textMuted: JBColor get() = JBColor.namedColor("Label.infoForeground", JBColor(0x818594, 0x6F737A))

  /** Links and clickable affordances. */
  val accent: JBColor get() = JBColor.lazy { JBUI.CurrentTheme.Link.Foreground.ENABLED }

  /** Separators and panel edges. */
  val border: JBColor get() = JBColor.namedColor("Component.borderColor", JBColor(0xEBECF0, 0x393B40))

  /** Work still owed: PENDING items, and an agent that has not called yet. */
  val statusPending: JBColor get() = JBColor.lazy { JBUI.CurrentTheme.Label.warningForeground() }

  /** Positive progress: ACKNOWLEDGED items, and an agent that called recently. */
  val statusActive: JBColor get() = JBColor.namedColor("Banner.successBackground", JBColor(0x2E9E45, 0x77C97D))

  /**
   * Closed and archived: RESOLVED / DISMISSED.
   *
   * A deliberately fixed blue rather than [accent]: a theme's accent is not guaranteed to read as
   * blue, and this has to stay distinct from [statusActive] and [statusPending] in the progress
   * ribbon, where all three sit side by side.
   */
  val statusDone: JBColor get() = JBColor.namedColor("Notification.MoreButton.foreground", JBColor(0x3574F0, 0x548AF7))

  /** Body text: notes and filenames. */
  val textPrimary: JBColor get() = JBColor.lazy { UIUtil.getLabelForeground() }

  /** The selected row. */
  val selectionBg: JBColor get() = JBColor.namedColor("List.selectionBackground", JBColor(0xD5E4FF, 0x2E436E))

  /** Something is wrong and the user has to act: a missing file, tools that never registered. */
  val statusError: JBColor get() = JBColor.namedColor("Component.errorFocusColor", JBColor(0xE53E4D, 0xF75464))

  /**
   * A tinted background for a pill: any token at 13%.
   *
   * Returns a [JBColor] that re-derives from [base] on each paint, so the tint tracks a theme switch
   * exactly as its source token does. Deriving it eagerly would hand back a frozen colour and undo
   * everything above.
   */
  fun soft(base: JBColor): JBColor = JBColor.lazy { ColorUtil.withAlpha(base, 0.13) }
}
