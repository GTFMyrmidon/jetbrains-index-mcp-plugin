package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase

class JavaScriptMemberResolverUnitTest : TestCase() {

    private fun element(start: Int, end: Int): PsiElement {
        val element = mockk<PsiElement>()
        every { element.textRange } returns TextRange(start, end)
        return element
    }

    private val resolver = JavaScriptMemberResolver(mockk<Project>(relaxed = true))

    fun testInsertionOffsetBeforeAndAfterAnchor() {
        val anchor = ResolvedMember(
            element = mockk(),
            name = "bar",
            kind = "function",
            signature = "bar()",
            parameterCount = 0,
            startOffset = 40,
            endOffset = 80,
            bodyStartOffset = 50,
            bodyEndOffset = 80,
            line = 4
        )

        val scope = mockk<PsiFile>()
        every { scope.textRange } returns TextRange(0, 200)

        assertEquals(40, resolver.getInsertionOffset(scope, "before", anchor))
        assertEquals(80, resolver.getInsertionOffset(scope, "after", anchor))
    }

    fun testFirstAndLastOnFileScope() {
        val scope = mockk<PsiFile>()
        every { scope.children } returns arrayOf()
        every { scope.textRange } returns TextRange(0, 250)

        assertEquals(0, resolver.getInsertionOffset(scope, "first", null))
        assertEquals(250, resolver.getInsertionOffset(scope, "last", null))
    }
}
