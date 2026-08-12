package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase

class GoMemberResolverUnitTest : TestCase() {

    private val resolver = GoMemberResolver(mockk<Project>(relaxed = true))

    fun testInsertionOffsetBeforeAndAfterAnchor() {
        val anchor = ResolvedMember(
            element = mockk(),
            name = "foo",
            kind = "function",
            signature = "func foo()",
            parameterCount = 0,
            startOffset = 50,
            endOffset = 100,
            bodyStartOffset = 70,
            bodyEndOffset = 100,
            line = 5
        )

        val scope = mockk<PsiFile>()
        every { scope.textRange } returns TextRange(0, 200)

        assertEquals(50, resolver.getInsertionOffset(scope, "before", anchor))
        assertEquals(100, resolver.getInsertionOffset(scope, "after", anchor))
    }

    fun testFirstAndLastOnFileScope() {
        val scope = mockk<PsiFile>()
        every { scope.children } returns arrayOf()
        every { scope.textRange } returns TextRange(0, 300)

        assertEquals(0, resolver.getInsertionOffset(scope, "first", null))
        assertEquals(300, resolver.getInsertionOffset(scope, "last", null))
    }
}
