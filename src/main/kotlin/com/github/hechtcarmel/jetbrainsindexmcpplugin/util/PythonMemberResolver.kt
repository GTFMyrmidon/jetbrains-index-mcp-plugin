package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class PythonMemberResolver(private val project: Project) : MemberResolver {

    companion object {
        private val LOG = logger<PythonMemberResolver>()

        private val pyFileClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.python.psi.PyFile") } catch (_: ClassNotFoundException) { null }
        }
        private val pyClassClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.python.psi.PyClass") } catch (_: ClassNotFoundException) { null }
        }
        private val pyFunctionClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.python.psi.PyFunction") } catch (_: ClassNotFoundException) { null }
        }
        private val pyTargetExpressionClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.python.psi.PyTargetExpression") } catch (_: ClassNotFoundException) { null }
        }
        private val pyStatementListClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.python.psi.PyStatementList") } catch (_: ClassNotFoundException) { null }
        }
        private val pyImportStatementBaseClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.python.psi.PyImportStatementBase") } catch (_: ClassNotFoundException) { null }
        }
    }

    override val languageId = "Python"

    override fun isAvailable(): Boolean = PluginDetectors.python.isAvailable && pyFileClass != null

    override fun findClass(psiFile: PsiFile, className: String?): PsiElement? {
        if (pyFileClass?.isInstance(psiFile) != true) return null

        val topClasses = getTopLevelClasses(psiFile)
        if (className == null) {
            return when {
                topClasses.size == 1 -> topClasses[0]
                else -> psiFile
            }
        }

        return findClassInDeclarations(topClasses, className)
    }

    private fun getTopLevelClasses(file: PsiFile): List<PsiElement> {
        return try {
            val topClassesMethod = file.javaClass.getMethod("getTopLevelClasses")
            @Suppress("UNCHECKED_CAST")
            (topClassesMethod.invoke(file) as? Array<*>)?.filterIsInstance<PsiElement>() ?: emptyList()
        } catch (_: Exception) {
            file.children.filter { pyClassClass?.isInstance(it) == true }
        }
    }

    private fun findClassInDeclarations(classes: List<PsiElement>, name: String): PsiElement? {
        for (cls in classes) {
            if (pyClassClass?.isInstance(cls) == true && getName(cls) == name) {
                return cls
            }
            if (pyClassClass?.isInstance(cls) == true) {
                val nested = getNestedClasses(cls)
                val found = findClassInDeclarations(nested, name)
                if (found != null) return found
            }
        }
        return null
    }

    private fun getNestedClasses(cls: PsiElement): List<PsiElement> {
        return try {
            val stmtList = getStatementList(cls) ?: return emptyList()
            stmtList.children.filter { pyClassClass?.isInstance(it) == true }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun findMembers(scope: PsiElement, memberName: String): List<ResolvedMember> {
        val declarations = if (pyFileClass?.isInstance(scope) == true) {
            getFileDeclarations(scope)
        } else if (pyClassClass?.isInstance(scope) == true) {
            getClassDeclarations(scope)
        } else {
            return emptyList()
        }

        val results = declarations.filter { getName(it) == memberName }.mapNotNull { resolveDeclaration(it) }
        if (results.isEmpty() && pyClassClass?.isInstance(scope) == true && getName(scope) == memberName) {
            return listOfNotNull(resolveDeclaration(scope))
        }
        return results
    }

    private fun getFileDeclarations(file: PsiElement): List<PsiElement> {
        return file.children.filter {
            pyFunctionClass?.isInstance(it) == true ||
            pyTargetExpressionClass?.isInstance(it) == true ||
            pyClassClass?.isInstance(it) == true
        }
    }

    private fun getClassDeclarations(cls: PsiElement): List<PsiElement> {
        val stmtList = getStatementList(cls) ?: return emptyList()
        val results = mutableListOf<PsiElement>()
        for (child in stmtList.children) {
            if (pyFunctionClass?.isInstance(child) == true ||
                pyClassClass?.isInstance(child) == true) {
                results.add(child)
            } else if (pyTargetExpressionClass?.isInstance(child) == true) {
                results.add(child)
            } else {
                // Statements in Python class bodies like assignment statements: `x = 5` or `x: int = 5`
                // pyTargetExpression might be nested inside PyAssignmentStatement or PyExpressionStatement
                val targetExprs = findTargetExpressionsInStatement(child)
                results.addAll(targetExprs)
            }
        }
        return results
    }

    private fun findTargetExpressionsInStatement(stmt: PsiElement): List<PsiElement> {
        if (pyTargetExpressionClass?.isInstance(stmt) == true) return listOf(stmt)
        val list = mutableListOf<PsiElement>()
        for (child in stmt.children) {
            if (pyTargetExpressionClass?.isInstance(child) == true) {
                list.add(child)
            }
        }
        return list
    }

    override fun getInsertionOffset(scope: PsiElement, position: String, anchor: ResolvedMember?): Int? {
        return when (position) {
            "before" -> {
                requireNotNull(anchor) { "anchor required for 'before' position" }
                anchor.startOffset
            }
            "after" -> {
                requireNotNull(anchor) { "anchor required for 'after' position" }
                anchor.endOffset
            }
            "first" -> {
                if (pyClassClass?.isInstance(scope) == true) {
                    val stmtList = getStatementList(scope) ?: return null
                    stmtList.textRange.startOffset
                } else if (scope is PsiFile) {
                    fileHeaderEndOffset(scope)
                } else scope.textRange.startOffset
            }
            else -> {
                if (pyClassClass?.isInstance(scope) == true) {
                    val stmtList = getStatementList(scope) ?: return null
                    stmtList.textRange.endOffset
                } else scope.textRange.endOffset
            }
        }
    }

    private fun fileHeaderEndOffset(file: PsiFile): Int {
        var lastImportEnd = 0
        for (child in file.children) {
            if (pyImportStatementBaseClass?.isInstance(child) == true) {
                lastImportEnd = maxOf(lastImportEnd, child.textRange.endOffset)
            }
        }
        return lastImportEnd
    }

    private fun resolveDeclaration(element: PsiElement): ResolvedMember? {
        val name = getName(element) ?: return null
        val line = MemberResolverUtils.getLineNumber(project, element) ?: return null

        return when {
            pyFunctionClass?.isInstance(element) == true -> {
                val stmtList = getStatementList(element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = "function",
                    signature = buildFunctionSignature(element),
                    parameterCount = getParameterCount(element),
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = stmtList?.textRange?.startOffset,
                    bodyEndOffset = stmtList?.textRange?.endOffset,
                    line = line
                )
            }
            pyTargetExpressionClass?.isInstance(element) == true -> {
                val assignedValue = getAssignedValue(element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = "field",
                    signature = null,
                    parameterCount = null,
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = assignedValue?.textRange?.startOffset,
                    bodyEndOffset = assignedValue?.textRange?.endOffset,
                    line = line
                )
            }
            pyClassClass?.isInstance(element) == true -> {
                val stmtList = getStatementList(element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = "class",
                    signature = null,
                    parameterCount = null,
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = stmtList?.textRange?.startOffset,
                    bodyEndOffset = stmtList?.textRange?.endOffset,
                    line = line
                )
            }
            else -> null
        }
    }

    private fun getStatementList(element: PsiElement): PsiElement? {
        return try {
            element.javaClass.getMethod("getStatementList").invoke(element) as? PsiElement
        } catch (_: Exception) {
            null
        }
    }

    private fun getAssignedValue(element: PsiElement): PsiElement? {
        return try {
            element.javaClass.getMethod("findAssignedValue").invoke(element) as? PsiElement
        } catch (_: Exception) {
            null
        }
    }

    private fun getName(element: PsiElement): String? {
        return try {
            element.javaClass.getMethod("getName").invoke(element) as? String
        } catch (_: Exception) {
            null
        }
    }

    private fun getParameterCount(function: PsiElement): Int? {
        return try {
            val paramList = function.javaClass.getMethod("getParameterList").invoke(function) as? PsiElement
                ?: return 0
            val params = paramList.javaClass.getMethod("getParameters").invoke(paramList) as? Array<*>
            params?.size ?: 0
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFunctionSignature(function: PsiElement): String {
        return try {
            val paramList = function.javaClass.getMethod("getParameterList").invoke(function) as? PsiElement
            val params = if (paramList != null) {
                val parameters = paramList.javaClass.getMethod("getParameters").invoke(paramList) as? Array<*>
                parameters?.filterIsInstance<PsiElement>()?.joinToString(", ") { it.text } ?: ""
            } else ""
            val name = getName(function) ?: "unknown"
            "def $name($params)"
        } catch (_: Exception) {
            getName(function) ?: "unknown"
        }
    }
}
