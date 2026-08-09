package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class JavaScriptMemberResolver(private val project: Project) : MemberResolver {

    companion object {
        private val LOG = logger<JavaScriptMemberResolver>()

        private val jsFileClass: Class<*>? by lazy {
            try { Class.forName("com.intellij.lang.javascript.psi.JSFile") } catch (_: ClassNotFoundException) { null }
        }
        private val jsClassClass: Class<*>? by lazy {
            try { Class.forName("com.intellij.lang.javascript.psi.ecmal4.JSClass") } catch (_: ClassNotFoundException) { null }
        }
        private val jsFunctionClass: Class<*>? by lazy {
            try { Class.forName("com.intellij.lang.javascript.psi.JSFunction") } catch (_: ClassNotFoundException) { null }
        }
        private val jsVariableClass: Class<*>? by lazy {
            try { Class.forName("com.intellij.lang.javascript.psi.JSVariable") } catch (_: ClassNotFoundException) { null }
        }
        private val jsBlockStatementClass: Class<*>? by lazy {
            try { Class.forName("com.intellij.lang.javascript.psi.JSBlockStatement") } catch (_: ClassNotFoundException) { null }
        }
        private val jsFieldClass: Class<*>? by lazy {
            try { Class.forName("com.intellij.lang.javascript.psi.JSField") } catch (_: ClassNotFoundException) { null }
        }
        private val jsImportStatementClass: Class<*>? by lazy {
            try { Class.forName("com.intellij.lang.javascript.psi.JSImportStatement") } catch (_: ClassNotFoundException) {
                try { Class.forName("com.intellij.lang.javascript.psi.ecmal4.JSImportStatement") } catch (_: ClassNotFoundException) { null }
            }
        }
    }

    override val languageId = "JavaScript"

    override fun isAvailable(): Boolean = PluginDetectors.javaScript.isAvailable && jsFileClass != null

    override fun findClass(psiFile: PsiFile, className: String?): PsiElement? {
        if (jsFileClass?.isInstance(psiFile) != true) return null

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
            if (jsClassClass?.isInstance(child) == true) {
                classes.add(child)
            } else {
                // Check if exported class, e.g., export class Foo {}
                val innerClass = findClassChild(child)
                if (innerClass != null) classes.add(innerClass)
            }
        }
        return classes
    }

    private fun findClassChild(element: PsiElement): PsiElement? {
        if (jsClassClass?.isInstance(element) == true) return element
        for (child in element.children) {
            if (jsClassClass?.isInstance(child) == true) return child
        }
        return null
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
        val nested = mutableListOf<PsiElement>()
        for (child in cls.children) {
            if (jsClassClass?.isInstance(child) == true) {
                nested.add(child)
            }
        }
        return nested
    }

    override fun findMembers(scope: PsiElement, memberName: String): List<ResolvedMember> {
        val declarations = if (jsFileClass?.isInstance(scope) == true) {
            getFileDeclarations(scope)
        } else if (jsClassClass?.isInstance(scope) == true) {
            getClassMembers(scope)
        } else {
            return emptyList()
        }

        val results = declarations.filter { getName(it) == memberName }.mapNotNull { resolveDeclaration(it) }
        if (results.isEmpty() && jsClassClass?.isInstance(scope) == true && getName(scope) == memberName) {
            return listOfNotNull(resolveDeclaration(scope))
        }
        return results
    }

    private fun getFileDeclarations(file: PsiElement): List<PsiElement> {
        val results = mutableListOf<PsiElement>()
        for (child in file.children) {
            if (jsFunctionClass?.isInstance(child) == true ||
                jsVariableClass?.isInstance(child) == true ||
                jsClassClass?.isInstance(child) == true) {
                results.add(child)
            } else if (child.children.isNotEmpty()) {
                // export statement wrapper, var statement wrapper (JSVarStatement contains JSVariable)
                for (sub in child.children) {
                    if (jsFunctionClass?.isInstance(sub) == true ||
                        jsVariableClass?.isInstance(sub) == true ||
                        jsClassClass?.isInstance(sub) == true) {
                        results.add(sub)
                    }
                }
            }
        }
        return results
    }

    private fun getClassMembers(cls: PsiElement): List<PsiElement> {
        val results = mutableListOf<PsiElement>()
        // In JSClass, functions/fields can be direct children or inside functions/fields array
        try {
            val functionsMethod = cls.javaClass.getMethod("getFunctions")
            val functions = (functionsMethod.invoke(cls) as? Array<*>)?.filterIsInstance<PsiElement>()
            if (functions != null) results.addAll(functions)
        } catch (_: Exception) {}

        try {
            val fieldsMethod = cls.javaClass.getMethod("getFields")
            val fields = (fieldsMethod.invoke(cls) as? Array<*>)?.filterIsInstance<PsiElement>()
            if (fields != null) results.addAll(fields)
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            for (child in cls.children) {
                if (jsFunctionClass?.isInstance(child) == true ||
                    jsVariableClass?.isInstance(child) == true ||
                    jsFieldClass?.isInstance(child) == true) {
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
                if (jsClassClass?.isInstance(scope) == true) {
                    val lBrace = getLBrace(scope)
                    lBrace?.textRange?.endOffset ?: scope.textRange.startOffset
                } else if (scope is PsiFile) {
                    fileHeaderEndOffset(scope)
                } else scope.textRange.startOffset
            }
            else -> {
                if (jsClassClass?.isInstance(scope) == true) {
                    val rBrace = getRBrace(scope)
                    rBrace?.textRange?.startOffset ?: scope.textRange.endOffset
                } else scope.textRange.endOffset
            }
        }
    }

    private fun getLBrace(cls: PsiElement): PsiElement? {
        return try {
            cls.javaClass.getMethod("getLBrace").invoke(cls) as? PsiElement
        } catch (_: Exception) {
            cls.children.firstOrNull { it.text == "{" }
        }
    }

    private fun getRBrace(cls: PsiElement): PsiElement? {
        return try {
            cls.javaClass.getMethod("getRBrace").invoke(cls) as? PsiElement
        } catch (_: Exception) {
            cls.children.lastOrNull { it.text == "}" }
        }
    }

    private fun fileHeaderEndOffset(file: PsiFile): Int {
        var lastImportEnd = 0
        for (child in file.children) {
            if (jsImportStatementClass?.isInstance(child) == true || child.text.startsWith("import ")) {
                lastImportEnd = maxOf(lastImportEnd, child.textRange.endOffset)
            }
        }
        return lastImportEnd
    }

    private fun resolveDeclaration(element: PsiElement): ResolvedMember? {
        val name = getName(element) ?: return null
        val line = MemberResolverUtils.getLineNumber(project, element) ?: return null

        return when {
            jsFunctionClass?.isInstance(element) == true -> {
                val (bodyStart, bodyEnd) = getFunctionBodyOffsets(element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = "function",
                    signature = buildFunctionSignature(element),
                    parameterCount = getParameterCount(element),
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = bodyStart,
                    bodyEndOffset = bodyEnd,
                    line = line
                )
            }
            jsVariableClass?.isInstance(element) == true || jsFieldClass?.isInstance(element) == true -> {
                val initializer = getInitializer(element)
                val isArrowFn = initializer != null && jsFunctionClass?.isInstance(initializer) == true
                if (isArrowFn) {
                    val (bodyStart, bodyEnd) = getFunctionBodyOffsets(initializer)
                    ResolvedMember(
                        element = element,
                        name = name,
                        kind = "function",
                        signature = buildFunctionSignature(initializer),
                        parameterCount = getParameterCount(initializer),
                        startOffset = element.textRange.startOffset,
                        endOffset = element.textRange.endOffset,
                        bodyStartOffset = bodyStart,
                        bodyEndOffset = bodyEnd,
                        line = line
                    )
                } else {
                    ResolvedMember(
                        element = element,
                        name = name,
                        kind = "field",
                        signature = null,
                        parameterCount = null,
                        startOffset = element.textRange.startOffset,
                        endOffset = element.textRange.endOffset,
                        bodyStartOffset = initializer?.textRange?.startOffset,
                        bodyEndOffset = initializer?.textRange?.endOffset,
                        line = line
                    )
                }
            }
            jsClassClass?.isInstance(element) == true -> {
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

    private fun getFunctionBodyOffsets(fn: PsiElement): Pair<Int?, Int?> {
        // Block body vs expression body (arrow function)
        val block = getBlockBody(fn)
        if (block != null) {
            val lBrace = getLBrace(block)
            val rBrace = getRBrace(block)
            if (lBrace != null && rBrace != null) {
                return Pair(lBrace.textRange.endOffset, rBrace.textRange.startOffset)
            }
        }
        // Check for expression body (e.g. arrow function (x) => x * 2)
        val bodyExpr = getBodyExpression(fn)
        if (bodyExpr != null) {
            return Pair(bodyExpr.textRange.startOffset, bodyExpr.textRange.endOffset)
        }
        return Pair(null, null)
    }

    private fun getBlockBody(fn: PsiElement): PsiElement? {
        try {
            val block = fn.javaClass.getMethod("getBlock").invoke(fn) as? PsiElement
            if (block != null) return block
        } catch (_: Exception) {}

        for (child in fn.children) {
            if (jsBlockStatementClass?.isInstance(child) == true) return child
        }
        return null
    }

    private fun getBodyExpression(fn: PsiElement): PsiElement? {
        try {
            val body = fn.javaClass.getMethod("getBody").invoke(fn) as? PsiElement
            if (body != null && jsBlockStatementClass?.isInstance(body) != true) return body
        } catch (_: Exception) {}
        return null
    }

    private fun getInitializer(varOrField: PsiElement): PsiElement? {
        return try {
            varOrField.javaClass.getMethod("getInitializer").invoke(varOrField) as? PsiElement
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
            "$name($params)"
        } catch (_: Exception) {
            getName(function) ?: "unknown"
        }
    }
}
