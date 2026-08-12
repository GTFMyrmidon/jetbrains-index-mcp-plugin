package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class PhpMemberResolver(private val project: Project) : MemberResolver {

    companion object {
        private val LOG = logger<PhpMemberResolver>()

        private val phpFileClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.php.lang.psi.PhpFile") } catch (e: ClassNotFoundException) { null }
        }
        private val phpClassClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.php.lang.psi.elements.PhpClass") } catch (e: ClassNotFoundException) { null }
        }
        private val methodClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.php.lang.psi.elements.Method") } catch (e: ClassNotFoundException) { null }
        }
        private val functionClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.php.lang.psi.elements.Function") } catch (e: ClassNotFoundException) { null }
        }
        private val fieldClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.php.lang.psi.elements.Field") } catch (e: ClassNotFoundException) { null }
        }
        private val phpUseClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.php.lang.psi.elements.PhpUse") } catch (e: ClassNotFoundException) { null }
        }
    }

    override val languageId = "PHP"

    override fun isAvailable(): Boolean = PluginDetectors.php.isAvailable && phpFileClass != null

    override fun findClass(psiFile: PsiFile, className: String?): PsiElement? {
        if (phpFileClass?.isInstance(psiFile) != true) return null

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
        val classes = mutableListOf<PsiElement>()
        for (child in file.children) {
            if (phpClassClass?.isInstance(child) == true) {
                classes.add(child)
            } else {
                // Classes inside namespace declarations
                for (sub in child.children) {
                    if (phpClassClass?.isInstance(sub) == true) {
                        classes.add(sub)
                    }
                }
            }
        }
        return classes
    }

    private fun findClassInDeclarations(classes: List<PsiElement>, name: String): PsiElement? {
        for (cls in classes) {
            if (getName(cls) == name) return cls
            val nested = getNestedClasses(cls)
            val found = findClassInDeclarations(nested, name)
            if (found != null) return found
        }
        return null
    }

    private fun getNestedClasses(cls: PsiElement): List<PsiElement> {
        return cls.children.filter { phpClassClass?.isInstance(it) == true }
    }

    override fun findMembers(scope: PsiElement, memberName: String): List<ResolvedMember> {
        val declarations = if (phpFileClass?.isInstance(scope) == true) {
            getFileDeclarations(scope)
        } else if (phpClassClass?.isInstance(scope) == true) {
            getClassMembers(scope)
        } else {
            emptyList()
        }

        val results = declarations.filter { getName(it) == memberName }.mapNotNull { resolveDeclaration(it) }
        if (results.isEmpty() && phpClassClass?.isInstance(scope) == true && getName(scope) == memberName) {
            return listOfNotNull(resolveDeclaration(scope))
        }
        return results
    }

    private fun getFileDeclarations(file: PsiElement): List<PsiElement> {
        val results = mutableListOf<PsiElement>()
        for (child in file.children) {
            if (functionClass?.isInstance(child) == true ||
                fieldClass?.isInstance(child) == true ||
                phpClassClass?.isInstance(child) == true) {
                results.add(child)
            } else if (child.children.isNotEmpty()) {
                for (sub in child.children) {
                    if (functionClass?.isInstance(sub) == true ||
                        fieldClass?.isInstance(sub) == true ||
                        phpClassClass?.isInstance(sub) == true) {
                        results.add(sub)
                    }
                }
            }
        }
        return results
    }

    private fun getClassMembers(cls: PsiElement): List<PsiElement> {
        val results = mutableListOf<PsiElement>()
        try {
            val methods = (cls.javaClass.getMethod("getOwnMethods").invoke(cls) as? Array<*>)?.filterIsInstance<PsiElement>()
            if (methods != null) results.addAll(methods)
        } catch (_: Exception) {}

        try {
            val fields = (cls.javaClass.getMethod("getOwnFields").invoke(cls) as? Array<*>)?.filterIsInstance<PsiElement>()
            if (fields != null) results.addAll(fields)
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            for (child in cls.children) {
                if (methodClass?.isInstance(child) == true ||
                    fieldClass?.isInstance(child) == true) {
                    results.add(child)
                }
            }
        }

        return results
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
                if (phpClassClass?.isInstance(scope) == true) {
                    val lBrace = getLBrace(scope)
                    lBrace?.textRange?.endOffset ?: scope.textRange.startOffset
                } else if (scope is PsiFile) {
                    fileHeaderEndOffset(scope)
                } else scope.textRange.startOffset
            }
            else -> {
                if (phpClassClass?.isInstance(scope) == true) {
                    val rBrace = getRBrace(scope)
                    rBrace?.textRange?.startOffset ?: scope.textRange.endOffset
                } else scope.textRange.endOffset
            }
        }
    }

    private fun getLBrace(cls: PsiElement): PsiElement? {
        return cls.children.firstOrNull { it.text == "{" }
    }

    private fun getRBrace(cls: PsiElement): PsiElement? {
        return cls.children.lastOrNull { it.text == "}" }
    }

    private fun fileHeaderEndOffset(file: PsiFile): Int {
        var lastHeaderEnd = 0
        for (child in file.children) {
            if (phpUseClass?.isInstance(child) == true || child.text.startsWith("<?php") || child.text.startsWith("namespace ") || child.text.startsWith("use ")) {
                lastHeaderEnd = maxOf(lastHeaderEnd, child.textRange.endOffset)
            }
        }
        return lastHeaderEnd
    }

    private fun resolveDeclaration(element: PsiElement): ResolvedMember? {
        val name = getName(element) ?: return null
        val line = MemberResolverUtils.getLineNumber(project, element) ?: return null

        return when {
            methodClass?.isInstance(element) == true || functionClass?.isInstance(element) == true -> {
                val lBrace = getLBrace(element)
                val rBrace = getRBrace(element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = if (methodClass?.isInstance(element) == true) "method" else "function",
                    signature = buildFunctionSignature(element),
                    parameterCount = getParameterCount(element),
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = lBrace?.textRange?.endOffset,
                    bodyEndOffset = rBrace?.textRange?.startOffset,
                    line = line
                )
            }
            fieldClass?.isInstance(element) == true -> {
                val defaultValue = getDefaultValue(element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = "field",
                    signature = null,
                    parameterCount = null,
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = defaultValue?.textRange?.startOffset,
                    bodyEndOffset = defaultValue?.textRange?.endOffset,
                    line = line
                )
            }
            phpClassClass?.isInstance(element) == true -> {
                val lBrace = getLBrace(element)
                val rBrace = getRBrace(element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = "class",
                    signature = null,
                    parameterCount = null,
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = lBrace?.textRange?.endOffset,
                    bodyEndOffset = rBrace?.textRange?.startOffset,
                    line = line
                )
            }
            else -> null
        }
    }

    private fun getDefaultValue(field: PsiElement): PsiElement? {
        return try {
            field.javaClass.getMethod("getDefaultValue").invoke(field) as? PsiElement
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
            val params = function.javaClass.getMethod("getParameters").invoke(function) as? Array<*>
            params?.size ?: 0
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFunctionSignature(function: PsiElement): String {
        return try {
            val name = getName(function) ?: "unknown"
            val params = (function.javaClass.getMethod("getParameters").invoke(function) as? Array<*>)
                ?.filterIsInstance<PsiElement>()
                ?.joinToString(", ") { it.text } ?: ""
            "function $name($params)"
        } catch (_: Exception) {
            getName(function) ?: "unknown"
        }
    }
}
