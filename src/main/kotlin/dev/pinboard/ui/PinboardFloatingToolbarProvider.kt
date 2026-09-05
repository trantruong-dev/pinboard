package dev.pinboard.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.util.Disposer

/**
 * Floats a "Pin for Agent" button over a selection.
 *
 * The keyboard shortcut is the fast path, but nothing on screen says the shortcut exists. Selecting
 * code is already the gesture that precedes pinning it, so the button appears exactly then and gets
 * out of the way again the moment the selection is dropped.
 */
class PinboardFloatingToolbarProvider : AbstractFloatingToolbarProvider(GROUP_ID) {

  override val autoHideable: Boolean = true

  /**
   * The real gate.
   *
   * The platform only builds the component - and installs its show-on-hover listener - for editors
   * this accepts, so [register] runs far too late to keep the button out of an editor. Restricting
   * it to a main editor is what keeps it off the commit message box, diff panes, the console and
   * previews. Checking "has a file" is not enough: the commit message box has one (a light file),
   * and only its [EditorKind] (UNTYPED) tells it apart.
   */
  override fun isApplicable(dataContext: DataContext): Boolean =
    dataContext.getData(CommonDataKeys.EDITOR)?.editorKind == EditorKind.MAIN_EDITOR

  override fun register(
    dataContext: DataContext,
    component: FloatingToolbarComponent,
    parentDisposable: Disposable,
  ) {
    val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return
    sync(editor, component)

    val listener = object : SelectionListener {
      override fun selectionChanged(e: SelectionEvent) = sync(editor, component)
    }
    editor.selectionModel.addSelectionListener(listener)
    Disposer.register(parentDisposable) { editor.selectionModel.removeSelectionListener(listener) }
  }

  /**
   * `hasSelection()` asserts read access, but the platform calls both [register] and the selection
   * events on the EDT without an implicit read lock, so this cannot simply ask the model.
   */
  private fun sync(editor: Editor, component: FloatingToolbarComponent) {
    val hasSelection = ReadAction.compute<Boolean, RuntimeException> {
      editor.selectionModel.hasSelection()
    }
    if (hasSelection) component.scheduleShow() else component.scheduleHide()
  }

  companion object {
    const val GROUP_ID = "Pinboard.FloatingToolbar"
  }
}
