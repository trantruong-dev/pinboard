package dev.pinboard.ui

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PinboardFloatingToolbarProviderTest : BasePlatformTestCase() {

  private fun contextFor(editor: Editor?) = SimpleDataContext.builder()
    .add(CommonDataKeys.PROJECT, project)
    .apply { if (editor != null) add(CommonDataKeys.EDITOR, editor) }
    .build()

  private fun withEditor(kind: EditorKind, body: (Editor) -> Unit) {
    val factory = EditorFactory.getInstance()
    val document = factory.createDocument("fun foo() {}\n")
    val editor = factory.createEditor(document, project, kind)
    try {
      body(editor)
    } finally {
      factory.releaseEditor(editor)
    }
  }

  fun testTheButtonIsOfferedInAMainEditor() {
    withEditor(EditorKind.MAIN_EDITOR) { editor ->
      assertTrue(PinboardFloatingToolbarProvider().isApplicable(contextFor(editor)))
    }
  }

  /**
   * The gate that matters. The platform builds the component - and its show-on-hover listener -
   * only for editors this accepts, so anything let through here gets a floating button pinned over
   * it for the rest of the session.
   *
   * UNTYPED is the commit message box, which is the case a "does it have a file?" check would miss:
   * it has one, just a light one.
   */
  fun testTheButtonIsKeptOutOfEveryOtherKindOfEditor() {
    val provider = PinboardFloatingToolbarProvider()
    for (kind in EditorKind.entries.filter { it != EditorKind.MAIN_EDITOR }) {
      withEditor(kind) { editor ->
        assertFalse("must not float over $kind", provider.isApplicable(contextFor(editor)))
      }
    }
  }

  fun testNoEditorMeansNoButton() {
    assertFalse(PinboardFloatingToolbarProvider().isApplicable(contextFor(null)))
  }

  /** Auto-hide is what keeps it from becoming permanent furniture over the code. */
  fun testTheButtonHidesItself() {
    assertTrue(PinboardFloatingToolbarProvider().autoHideable)
  }
}
