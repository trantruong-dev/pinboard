package dev.pinboard.capture

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiManager

/**
 * Resolves a PSI symbol path like `com.foo.Bar#baz(int)` for a given offset in a file.
 * The agent uses it to relocate stale feedback after lines have shifted.
 *
 * Must run inside a [ReadAction] (callers use [resolve]). Languages without a PSI structure
 * return null instead of throwing.
 */
object SymbolPathResolver {

  fun resolve(project: Project, file: VirtualFile, offset: Int): String? {
    if (offset < 0) return null
    return ReadAction.compute<String?, Throwable> {
      try {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@compute null
        val element = psiFile.findElementAt(offset) ?: return@compute null
        buildSymbolPath(element, psiFile)
      } catch (_: Throwable) {
        // PSI can throw PsiInvalidElementAccessException etc. Plan requires null, not throw.
        null
      }
    }
  }

  private fun buildSymbolPath(element: PsiElement, psiFile: PsiFile): String? {
    val packageName = findPackageName(psiFile)
    val chain = mutableListOf<String>()
    var current: PsiElement? = element
    while (current != null && current !== psiFile) {
      if (current is PsiNamedElement) {
        val name = current.name
        if (!name.isNullOrEmpty() && name !in chain) {
          chain.add(name)
        }
      }
      current = current.parent
    }
    if (chain.isEmpty()) return null
    val qualified = if (packageName != null && packageName.isNotEmpty()) {
      "$packageName.${chain.joinToString("#")}"
    } else {
      chain.joinToString("#")
    }
    return qualified
  }

  private fun findPackageName(psiFile: PsiFile): String? {
    return try {
      // Java/Kotlin-like: the package statement is a PsiElement whose text starts with "package".
      // Kotlin allows omitting the trailing ';', so trim it optionally instead of requiring it.
      var current: PsiElement? = psiFile.firstChild
      while (current != null) {
        val text = current.text?.trim()
        if (text != null && text.startsWith("package ")) {
          return text.removePrefix("package ").trimEnd(';').trim()
        }
        current = current.nextSibling
      }
      null
    } catch (_: Throwable) {
      null
    }
  }
}