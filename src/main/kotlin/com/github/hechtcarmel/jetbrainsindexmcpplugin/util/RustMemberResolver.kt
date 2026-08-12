package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class RustMemberResolver(private val project: Project) : MemberResolver {

    companion object {
        private val LOG = logger<RustMemberResolver>()

        private val rsFileClass: Class<*>? by lazy {
            try { Class.forName("org.rust.lang.core.psi.RsFile") } catch (e: ClassNotFoundException) { null }
        }
        private val rsStructItemClass: Class<*>? by lazy {
            try { Class.forName("org.rust.lang.core.psi.RsStructItem") } catch (e: ClassNotFoundException) { null }
        }
        private val rsEnumItemClass: Class<*>? by lazy {
            try { Class.forName("org.rust.lang.core.psi.RsEnumItem") } catch (e: ClassNotFoundException) { null }
        }
        private val rsTraitItemClass: Class<*>? by lazy {
            try { Class.forName("org.rust.lang.core.psi.RsTraitItem") } catch (e: ClassNotFoundException) { null }
        }
        private val rsImplItemClass: Class<*>? by lazy {
            try { Class.forName("org.rust.lang.core.psi.RsImplItem") } catch (e: ClassNotFoundException) { null }
        }
        private val rsFunctionClass: Class<*>? by lazy {
            try { Class.forName("org.rust.lang.core.psi.RsFunction") } catch (e: ClassNotFoundException) { null }
        }
        private val rsBlockClass: Class<*>? by lazy {
            try { Class.forName("org.rust.lang.core.psi.RsBlock") } catch (e: ClassNotFoundException) { null }
        }
        private val rsUseItemClass: Class<*>? by lazy {
            try { Class.forName("org.rust.lang.core.psi.RsUseItem") } catch (e: ClassNotFoundException) { null }
        }
    }

    override val languageId = "Rust"

    override fun isAvailable(): Boolean = PluginDetectors.rust.isAvailable && rsFileClass != null

    override fun findClass(psiFile: PsiFile, className: String?): PsiElement? {
        if (rsFileClass?.isInstance(psiFile) != true) return null

        val topItems = getTopLevelItems(psiFile)
        if (className == null) {
            return when {
                topItems.size == 1 -> topItems[0]
                else -> psiFile
            }
        }

        return topItems.firstOrNull { getName(it) == className }
    }

    private fun getTopLevelItems(file: PsiFile): List<PsiElement> {
        return file.children.filter {
            rsStructItemClass?.isInstance(it) == true ||
            rsEnumItemClass?.isInstance(it) == true ||
            rsTraitItemClass?.isInstance(it) == true ||
            rsImplItemClass?.isInstance(it) == true
        }
    }

    override fun findMembers(scope: PsiElement, memberName: String): List<ResolvedMember> {
        val declarations = if (rsFileClass?.isInstance(scope) == true) {
            getFileDeclarations(scope)
        } else if (isRustContainer(scope)) {
            getContainerMembers(scope)
        } else {
            emptyList()
        }

        val results = declarations.filter { getName(it) == memberName }.mapNotNull { resolveDeclaration(it) }
        if (results.isEmpty() && isRustContainer(scope) && getName(scope) == memberName) {
            return listOfNotNull(resolveDeclaration(scope))
        }
        return results
    }

    private fun isRustContainer(element: PsiElement): Boolean {
        return rsStructItemClass?.isInstance(element) == true ||
               rsEnumItemClass?.isInstance(element) == true ||
               rsTraitItemClass?.isInstance(element) == true ||
               rsImplItemClass?.isInstance(element) == true
    }

    private fun getFileDeclarations(file: PsiElement): List<PsiElement> {
        return file.children.filter {
            rsFunctionClass?.isInstance(it) == true ||
            isRustContainer(it)
        }
    }

    private fun getContainerMembers(container: PsiElement): List<PsiElement> {
        val members = mutableListOf<PsiElement>()
        for (child in container.children) {
            if (rsFunctionClass?.isInstance(child) == true) {
                members.add(child)
            } else if (child.children.isNotEmpty()) {
                members.addAll(child.children.filter { rsFunctionClass?.isInstance(it) == true })
            }
        }
        return members
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
                if (isRustContainer(scope)) {
                    val lBrace = getLBrace(scope)
                    lBrace?.textRange?.endOffset ?: scope.textRange.startOffset
                } else if (scope is PsiFile) {
                    fileHeaderEndOffset(scope)
                } else scope.textRange.startOffset
            }
            else -> {
                if (isRustContainer(scope)) {
                    val rBrace = getRBrace(scope)
                    rBrace?.textRange?.startOffset ?: scope.textRange.endOffset
                } else scope.textRange.endOffset
            }
        }
    }

    private fun getLBrace(element: PsiElement): PsiElement? {
        return element.children.firstOrNull { it.text == "{" }
    }

    private fun getRBrace(element: PsiElement): PsiElement? {
        return element.children.lastOrNull { it.text == "}" }
    }

    private fun fileHeaderEndOffset(file: PsiFile): Int {
        var lastHeaderEnd = 0
        for (child in file.children) {
            if (rsUseItemClass?.isInstance(child) == true || child.text.startsWith("use ")) {
                lastHeaderEnd = maxOf(lastHeaderEnd, child.textRange.endOffset)
            }
        }
        return lastHeaderEnd
    }

    private fun resolveDeclaration(element: PsiElement): ResolvedMember? {
        val name = getName(element) ?: return null
        val line = MemberResolverUtils.getLineNumber(project, element) ?: return null

        return when {
            rsFunctionClass?.isInstance(element) == true -> {
                val block = getBlock(element)
                val lBrace = block?.let { getLBrace(it) }
                val rBrace = block?.let { getRBrace(it) }
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = "function",
                    signature = buildFunctionSignature(element),
                    parameterCount = getParameterCount(element),
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = lBrace?.textRange?.endOffset ?: block?.textRange?.startOffset,
                    bodyEndOffset = rBrace?.textRange?.startOffset ?: block?.textRange?.endOffset,
                    line = line
                )
            }
            isRustContainer(element) -> {
                val lBrace = getLBrace(element)
                val rBrace = getRBrace(element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = determineContainerKind(element),
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

    private fun determineContainerKind(element: PsiElement): String {
        return when {
            rsStructItemClass?.isInstance(element) == true -> "struct"
            rsEnumItemClass?.isInstance(element) == true -> "enum"
            rsTraitItemClass?.isInstance(element) == true -> "trait"
            rsImplItemClass?.isInstance(element) == true -> "impl"
            else -> "container"
        }
    }

    private fun getBlock(element: PsiElement): PsiElement? {
        try {
            val block = element.javaClass.getMethod("getBlock").invoke(element) as? PsiElement
            if (block != null) return block
        } catch (_: Exception) {}
        return element.children.firstOrNull { rsBlockClass?.isInstance(it) == true }
    }

    private fun getName(element: PsiElement): String? {
        return try {
            element.javaClass.getMethod("getName").invoke(element) as? String
        } catch (_: Exception) {
            element.text.takeWhile { it != ' ' && it != '{' }
        }
    }

    private fun getParameterCount(function: PsiElement): Int? {
        return try {
            val valueParamList = function.javaClass.getMethod("getValueParameterList").invoke(function) as? PsiElement ?: return 0
            val params = valueParamList.javaClass.getMethod("getValueParameterList").invoke(valueParamList) as? List<*>
                ?: valueParamList.children.filter { it.text.contains(":") || it.text == "self" || it.text.startsWith("&self") }
            params.size
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFunctionSignature(function: PsiElement): String {
        return try {
            val name = getName(function) ?: "unknown"
            val valueParamList = function.javaClass.getMethod("getValueParameterList").invoke(function) as? PsiElement
            val text = valueParamList?.text ?: "()"
            "fn $name$text"
        } catch (_: Exception) {
            getName(function) ?: "unknown"
        }
    }
}
