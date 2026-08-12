package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoMemberResolver(private val project: Project) : MemberResolver {

    companion object {
        private val LOG = logger<GoMemberResolver>()

        private val goFileClass: Class<*>? by lazy {
            try { Class.forName("com.goide.psi.GoFile") } catch (_: ClassNotFoundException) { null }
        }
        private val goTypeSpecClass: Class<*>? by lazy {
            try { Class.forName("com.goide.psi.GoTypeSpec") } catch (_: ClassNotFoundException) { null }
        }
        private val goFunctionDeclarationClass: Class<*>? by lazy {
            try { Class.forName("com.goide.psi.GoFunctionDeclaration") } catch (_: ClassNotFoundException) { null }
        }
        private val goMethodDeclarationClass: Class<*>? by lazy {
            try { Class.forName("com.goide.psi.GoMethodDeclaration") } catch (_: ClassNotFoundException) { null }
        }
        private val goStructTypeClass: Class<*>? by lazy {
            try { Class.forName("com.goide.psi.GoStructType") } catch (_: ClassNotFoundException) { null }
        }
        private val goInterfaceTypeClass: Class<*>? by lazy {
            try { Class.forName("com.goide.psi.GoInterfaceType") } catch (_: ClassNotFoundException) { null }
        }
        private val goBlockClass: Class<*>? by lazy {
            try { Class.forName("com.goide.psi.GoBlock") } catch (_: ClassNotFoundException) { null }
        }
        private val goImportListClass: Class<*>? by lazy {
            try { Class.forName("com.goide.psi.GoImportList") } catch (_: ClassNotFoundException) { null }
        }
        private val goFieldDefinitionClass: Class<*>? by lazy {
            try { Class.forName("com.goide.psi.GoFieldDefinition") } catch (_: ClassNotFoundException) { null }
        }
    }

    override val languageId = "go"

    override fun isAvailable(): Boolean = PluginDetectors.go.isAvailable && goFileClass != null

    override fun findClass(psiFile: PsiFile, className: String?): PsiElement? {
        if (goFileClass?.isInstance(psiFile) != true) return null

        val topTypes = getTopLevelTypes(psiFile)
        if (className == null) {
            return when {
                topTypes.size == 1 -> topTypes[0]
                else -> psiFile
            }
        }

        return topTypes.firstOrNull { getName(it) == className }
    }

    private fun getTopLevelTypes(file: PsiFile): List<PsiElement> {
        return file.children.filter { goTypeSpecClass?.isInstance(it) == true }
    }

    override fun findMembers(scope: PsiElement, memberName: String): List<ResolvedMember> {
        val declarations = if (goFileClass?.isInstance(scope) == true) {
            getFileDeclarations(scope)
        } else if (goTypeSpecClass?.isInstance(scope) == true) {
            getTypeMembers(scope)
        } else {
            emptyList()
        }

        val results = declarations.filter { getName(it) == memberName }.mapNotNull { resolveDeclaration(it) }
        if (results.isEmpty() && goTypeSpecClass?.isInstance(scope) == true && getName(scope) == memberName) {
            return listOfNotNull(resolveDeclaration(scope))
        }
        return results
    }

    private fun getFileDeclarations(file: PsiElement): List<PsiElement> {
        return file.children.filter {
            goFunctionDeclarationClass?.isInstance(it) == true ||
            goMethodDeclarationClass?.isInstance(it) == true ||
            goTypeSpecClass?.isInstance(it) == true
        }
    }

    private fun getTypeMembers(typeSpec: PsiElement): List<PsiElement> {
        val results = mutableListOf<PsiElement>()
        val file = typeSpec.containingFile
        if (file != null) {
            val typeName = getName(typeSpec)
            // Collect methods with receiver matching typeName
            for (child in file.children) {
                if (goMethodDeclarationClass?.isInstance(child) == true) {
                    val receiverTypeName = getReceiverTypeName(child)
                    if (receiverTypeName == typeName) {
                        results.add(child)
                    }
                }
            }
        }

        // Struct fields
        val specType = getSpecType(typeSpec)
        if (specType != null && goStructTypeClass?.isInstance(specType) == true) {
            results.addAll(getStructFields(specType))
        }

        return results
    }

    private fun getSpecType(typeSpec: PsiElement): PsiElement? {
        return try {
            typeSpec.javaClass.getMethod("getSpecType").invoke(typeSpec) as? PsiElement
        } catch (_: Exception) {
            null
        }
    }

    private fun getStructFields(structType: PsiElement): List<PsiElement> {
        val fields = mutableListOf<PsiElement>()
        for (child in structType.children) {
            if (goFieldDefinitionClass?.isInstance(child) == true) {
                fields.add(child)
            } else {
                fields.addAll(child.children.filter { goFieldDefinitionClass?.isInstance(it) == true })
            }
        }
        return fields
    }

    private fun getReceiverTypeName(methodDecl: PsiElement): String? {
        return try {
            val receiver = methodDecl.javaClass.getMethod("getReceiver").invoke(methodDecl) as? PsiElement ?: return null
            val type = receiver.javaClass.getMethod("getType").invoke(receiver) as? PsiElement ?: return null
            type.text.removePrefix("*").trim()
        } catch (_: Exception) {
            null
        }
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
                if (goTypeSpecClass?.isInstance(scope) == true) {
                    val specType = getSpecType(scope)
                    val lBrace = getLBrace(specType ?: scope)
                    lBrace?.textRange?.endOffset ?: scope.textRange.startOffset
                } else if (scope is PsiFile) {
                    fileHeaderEndOffset(scope)
                } else scope.textRange.startOffset
            }
            else -> {
                if (goTypeSpecClass?.isInstance(scope) == true) {
                    val specType = getSpecType(scope)
                    val rBrace = getRBrace(specType ?: scope)
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
        var headerEnd = 0
        for (child in file.children) {
            if (goImportListClass?.isInstance(child) == true || child.text.startsWith("package ") || child.text.startsWith("import ")) {
                headerEnd = maxOf(headerEnd, child.textRange.endOffset)
            }
        }
        return headerEnd
    }

    private fun resolveDeclaration(element: PsiElement): ResolvedMember? {
        val name = getName(element) ?: return null
        val line = MemberResolverUtils.getLineNumber(project, element) ?: return null

        return when {
            goFunctionDeclarationClass?.isInstance(element) == true || goMethodDeclarationClass?.isInstance(element) == true -> {
                val block = getBlock(element)
                val lBrace = block?.let { getLBrace(it) }
                val rBrace = block?.let { getRBrace(it) }
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = if (goMethodDeclarationClass?.isInstance(element) == true) "method" else "function",
                    signature = buildFunctionSignature(element),
                    parameterCount = getParameterCount(element),
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = lBrace?.textRange?.endOffset ?: block?.textRange?.startOffset,
                    bodyEndOffset = rBrace?.textRange?.startOffset ?: block?.textRange?.endOffset,
                    line = line
                )
            }
            goTypeSpecClass?.isInstance(element) == true -> {
                val specType = getSpecType(element)
                val lBrace = getLBrace(specType ?: element)
                val rBrace = getRBrace(specType ?: element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = "type",
                    signature = null,
                    parameterCount = null,
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = lBrace?.textRange?.endOffset,
                    bodyEndOffset = rBrace?.textRange?.startOffset,
                    line = line
                )
            }
            goFieldDefinitionClass?.isInstance(element) == true -> {
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = "field",
                    signature = null,
                    parameterCount = null,
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = null,
                    bodyEndOffset = null,
                    line = line
                )
            }
            else -> null
        }
    }

    private fun getBlock(element: PsiElement): PsiElement? {
        try {
            val block = element.javaClass.getMethod("getBlock").invoke(element) as? PsiElement
            if (block != null) return block
        } catch (_: Exception) {}
        return element.children.firstOrNull { goBlockClass?.isInstance(it) == true }
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
            val signature = function.javaClass.getMethod("getSignature").invoke(function) as? PsiElement ?: return 0
            val paramList = signature.javaClass.getMethod("getParameters").invoke(signature) as? PsiElement ?: return 0
            val params = paramList.javaClass.getMethod("getParameterDeclarationList").invoke(paramList) as? List<*>
            params?.size ?: 0
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFunctionSignature(function: PsiElement): String {
        return try {
            val name = getName(function) ?: "unknown"
            val signature = function.javaClass.getMethod("getSignature").invoke(function) as? PsiElement
            val text = signature?.text ?: ""
            "func $name$text"
        } catch (_: Exception) {
            getName(function) ?: "unknown"
        }
    }
}
