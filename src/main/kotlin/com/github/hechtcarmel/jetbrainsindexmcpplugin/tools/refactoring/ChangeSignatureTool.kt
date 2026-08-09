package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.annotations.TestOnly

class ChangeSignatureTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<ChangeSignatureTool>()
    }

    /**
     * Test hook replacing the `processor.run()` call, so tests can reproduce the production
     * abort paths where `BaseRefactoringProcessor.run()` returns normally without applying
     * anything (read-only files, conflict dialogs, dumb mode). In unit-test mode the platform
     * converts those aborts into exceptions before `run()` returns, so they cannot be
     * triggered for real.
     */
    @TestOnly
    internal var processorRunHook: (() -> Unit)? = null

    override val name = ToolNames.CHANGE_SIGNATURE

    override val description = """
        Change a method's signature and automatically update all callers, overrides, and implementations.

        Supports Java, Python, and JavaScript/TypeScript.
        Can modify: method name, return type, visibility, parameters (add, remove, reorder, change types).
        New parameters get a default value inserted at all call sites.

        Examples:
        - Add parameter: {"file": "src/Service.java", "line": 15, "column": 10, "newParameters": [{"oldIndex": 0, "name": "id", "type": "String"}, {"oldIndex": -1, "name": "validate", "type": "boolean", "defaultValue": "true"}]}
        - Change return type: {"file": "src/Service.java", "line": 15, "column": 10, "newReturnType": "Optional<User>"}
        - Python rename: {"file": "src/service.py", "line": 10, "column": 5, "newName": "new_service"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to file containing the method. REQUIRED.")
        .lineAndColumn(required = true)
        .stringProperty(ParamNames.NEW_NAME, "New method name. Omit to keep current name.")
        .stringProperty(ParamNames.NEW_RETURN_TYPE, "New return type as a string (e.g., 'void', 'Optional<User>'). Omit to keep current.")
        .stringProperty(ParamNames.NEW_VISIBILITY, "New visibility: 'public', 'protected', 'private', or 'package-private'. Omit to keep current.")
        .property(ParamNames.NEW_PARAMETERS, kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("array"))
            put("description", kotlinx.serialization.json.JsonPrimitive("New parameter list. Each entry: {oldIndex (int, -1 for new), name (string), type (string), defaultValue (string, optional for new params)}. Omit to keep current parameters."))
            put("items", kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            })
        })
        .booleanProperty(ParamNames.GENERATE_DELEGATE, "Generate a delegation method with the old signature. Default: false.")
        .build()

    @Serializable
    data class ChangeSignatureResult(
        val success: Boolean,
        val file: String,
        val message: String,
        val affectedFiles: List<String> = emptyList(),
        val changesCount: Int = 0
    )

    private data class SignaturePreparation(
        val method: PsiMethod,
        val relativePath: String
    )

    private data class SignatureState(
        val name: String,
        val returnTypeText: String?,
        val visibility: String,
        val parameters: List<Pair<String, String>>
    )

    private data class SignatureVerification(
        val pointer: SmartPsiElementPointer<PsiMethod>,
        val before: SignatureState,
        val targetName: String?,
        val targetReturnTypeText: String?,
        val targetVisibility: String?,
        val targetParameters: List<Pair<String, String>>?
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val filePath = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments[ParamNames.LINE]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")

        val newName = arguments[ParamNames.NEW_NAME]?.jsonPrimitive?.content
        val newReturnType = arguments[ParamNames.NEW_RETURN_TYPE]?.jsonPrimitive?.content
        val newVisibility = arguments[ParamNames.NEW_VISIBILITY]?.jsonPrimitive?.content
        val newParametersJson = arguments[ParamNames.NEW_PARAMETERS]?.jsonArray
        val generateDelegate = arguments[ParamNames.GENERATE_DELEGATE]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        if (newName == null && newReturnType == null && newVisibility == null && newParametersJson == null) {
            return createErrorResult("At least one change is required: newName, newReturnType, newVisibility, or newParameters.")
        }

        if (newVisibility != null && newVisibility !in listOf("public", "protected", "private", "package-private", "package-local")) {
            return createErrorResult("Invalid visibility: '$newVisibility'. Must be: public, protected, private, or package-private.")
        }

        val virtualFile = resolveFile(project, filePath)
            ?: return createErrorResult("File not found: $filePath")
        ensureWritable(virtualFile)?.let { return it }

        val psiFile = suspendingReadAction {
            PsiManager.getInstance(project).findFile(virtualFile)
        } ?: return createErrorResult("Cannot resolve PSI for: $filePath")

        return when (psiFile.language.id) {
            "JAVA" -> executeJavaChangeSignature(
                project, psiFile, virtualFile, filePath, line, column,
                newName, newReturnType, newVisibility, newParametersJson, generateDelegate
            )
            "Python" -> executePythonChangeSignature(
                project, psiFile, virtualFile, filePath, line, column,
                newName, newParametersJson
            )
            "JavaScript", "TypeScript", "TypeScript JSX", "JSX Harmony", "ECMAScript 6" -> executeJsChangeSignature(
                project, psiFile, virtualFile, filePath, line, column,
                newName, newReturnType, newParametersJson
            )
            else -> createErrorResult("Change signature not supported for ${psiFile.language.displayName}. Supported: Java, Python, JavaScript, TypeScript.")
        }
    }

    private suspend fun executeJavaChangeSignature(
        project: Project,
        psiFile: PsiFile,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        filePath: String,
        line: Int,
        column: Int,
        newName: String?,
        newReturnType: String?,
        newVisibility: String?,
        newParametersJson: kotlinx.serialization.json.JsonArray?,
        generateDelegate: Boolean
    ): CallToolResult {
        val changeSignatureProcessorClass = try {
            Class.forName("com.intellij.refactoring.changeSignature.ChangeSignatureProcessor")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("Change signature not available — requires Java plugin.")
        }

        val javaChangeInfoImplClass = try {
            Class.forName("com.intellij.refactoring.changeSignature.JavaChangeInfoImpl")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("JavaChangeInfoImpl not available — requires Java plugin.")
        }

        val parameterInfoImplClass = try {
            Class.forName("com.intellij.refactoring.changeSignature.ParameterInfoImpl")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("ParameterInfoImpl not available — requires Java plugin.")
        }

        val prep = suspendingReadAction {
            prepareChange(project, virtualFile, filePath, line, column)
        }

        return when {
            prep.isFailure -> createErrorResult(prep.exceptionOrNull()?.message ?: "Failed to prepare change")
            else -> {
                val p = prep.getOrThrow()
                applyChange(
                    project, p, newName, newReturnType, newVisibility, newParametersJson,
                    generateDelegate, changeSignatureProcessorClass, javaChangeInfoImplClass, parameterInfoImplClass
                )
            }
        }
    }

    private suspend fun executePythonChangeSignature(
        project: Project,
        psiFile: PsiFile,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        filePath: String,
        line: Int,
        column: Int,
        newName: String?,
        newParametersJson: kotlinx.serialization.json.JsonArray?
    ): CallToolResult {
        val pyFunctionClass = try {
            Class.forName("com.jetbrains.python.psi.PyFunction")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("Change signature not available — requires Python plugin.")
        }

        val pyChangeSignatureProcessorClass = try {
            Class.forName("com.jetbrains.python.refactoring.changeSignature.PyChangeSignatureProcessor")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("Change signature not available — requires Python plugin.")
        }

        val pyParameterInfoClass = try {
            Class.forName("com.jetbrains.python.refactoring.changeSignature.PyParameterInfo")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("PyParameterInfo not available — requires Python plugin.")
        }

        val pyFunction = suspendingReadAction {
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@suspendingReadAction null
            if (line < 1 || line > document.lineCount) return@suspendingReadAction null
            val offset = document.getLineStartOffset(line - 1) + (column - 1).coerceAtLeast(0)
            val element = psiFile.findElementAt(offset) ?: return@suspendingReadAction null
            PsiTreeUtil.getParentOfType(element, pyFunctionClass as Class<out PsiElement>)
        } ?: return createErrorResult("No function found at line $line, column $column. Position the cursor on a function name.")

        val relativePath = ProjectUtils.getToolFilePath(project, virtualFile)

        return try {
            val (processor, affectedFiles) = suspendingReadAction {
                val currentParamsMethod = pyFunction.javaClass.getMethod("getParameterList")
                val currentParamScope = currentParamsMethod.invoke(pyFunction) as? PsiElement
                val currentParams = if (currentParamScope != null) {
                    (currentParamScope.javaClass.getMethod("getParameters").invoke(currentParamScope) as? Array<*>)?.toList() ?: emptyList()
                } else emptyList()

                val paramInfos = mutableListOf<Any>()
                if (newParametersJson != null) {
                    for (paramJson in newParametersJson) {
                        val obj = paramJson.jsonObject
                        val oldIndex = obj["oldIndex"]?.jsonPrimitive?.int ?: -1
                        val name = obj["name"]?.jsonPrimitive?.content ?: ""
                        val defaultValue = obj["defaultValue"]?.jsonPrimitive?.content ?: ""

                        val paramCtor = pyParameterInfoClass.constructors.firstOrNull { ctor ->
                            ctor.parameterCount >= 3 && ctor.parameterTypes[0] == Integer.TYPE && ctor.parameterTypes[1] == String::class.java
                        } ?: throw Exception("Cannot locate PyParameterInfo constructor")

                        val instance = when (paramCtor.parameterCount) {
                            3 -> paramCtor.newInstance(oldIndex, name, defaultValue)
                            4 -> paramCtor.newInstance(oldIndex, name, defaultValue, false)
                            else -> {
                                val args = arrayOfNulls<Any>(paramCtor.parameterCount)
                                args[0] = oldIndex
                                args[1] = name
                                args[2] = defaultValue
                                args[3] = false
                                paramCtor.newInstance(*args)
                            }
                        }
                        paramInfos.add(instance)
                    }
                } else {
                    currentParams.forEachIndexed { i, p ->
                        if (p != null) {
                            val name = p.javaClass.getMethod("getName").invoke(p) as? String ?: ""
                            val paramCtor = pyParameterInfoClass.constructors.firstOrNull { ctor ->
                                ctor.parameterCount >= 3 && ctor.parameterTypes[0] == Integer.TYPE
                            }
                            if (paramCtor != null) {
                                val args = arrayOfNulls<Any>(paramCtor.parameterCount)
                                args[0] = i
                                args[1] = name
                                args[2] = ""
                                if (args.size > 3) args[3] = false
                                paramInfos.add(paramCtor.newInstance(*args))
                            }
                        }
                    }
                }

                val targetName = newName ?: (pyFunction.javaClass.getMethod("getName").invoke(pyFunction) as? String ?: "")

                val procCtor = pyChangeSignatureProcessorClass.constructors.firstOrNull { ctor ->
                    ctor.parameterCount >= 3 && ctor.parameterTypes[0] == Project::class.java
                } ?: throw Exception("Cannot locate PyChangeSignatureProcessor constructor")

                val procArgs = arrayOfNulls<Any>(procCtor.parameterCount)
                procArgs[0] = project
                procArgs[1] = pyFunction
                procArgs[2] = targetName
                if (procArgs.size > 3) {
                    val listType = procCtor.parameterTypes[3]
                    if (listType.isArray) {
                        val arr = java.lang.reflect.Array.newInstance(pyParameterInfoClass, paramInfos.size)
                        paramInfos.forEachIndexed { idx, item -> java.lang.reflect.Array.set(arr, idx, item) }
                        procArgs[3] = arr
                    } else {
                        procArgs[3] = paramInfos
                    }
                }
                if (procArgs.size > 4) procArgs[4] = false

                val proc = procCtor.newInstance(*procArgs) as com.intellij.refactoring.BaseRefactoringProcessor
                proc to relativePath
            }

            edtAction {
                processor.setPreviewUsages(false)
                val hook = processorRunHook
                if (hook != null) hook() else processor.run()
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveAllDocuments()
            }

            createJsonResult(ChangeSignatureResult(
                success = true,
                file = affectedFiles,
                message = "Changed Python signature of function",
                affectedFiles = listOf(affectedFiles),
                changesCount = 1
            ))
        } catch (e: Throwable) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            createErrorResult("Python change signature failed: ${cause.message}")
        }
    }

    private suspend fun executeJsChangeSignature(
        project: Project,
        psiFile: PsiFile,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        filePath: String,
        line: Int,
        column: Int,
        newName: String?,
        newReturnType: String?,
        newParametersJson: kotlinx.serialization.json.JsonArray?
    ): CallToolResult {
        val jsFunctionClass = try {
            Class.forName("com.intellij.lang.javascript.psi.JSFunction")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("Change signature not available — requires JavaScript plugin.")
        }

        val jsChangeSignatureProcessorClass = try {
            Class.forName("com.intellij.lang.javascript.refactoring.changeSignature.JSChangeSignatureProcessor")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("Change signature not available — requires JavaScript plugin.")
        }

        val jsFunction = suspendingReadAction {
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@suspendingReadAction null
            if (line < 1 || line > document.lineCount) return@suspendingReadAction null
            val offset = document.getLineStartOffset(line - 1) + (column - 1).coerceAtLeast(0)
            val element = psiFile.findElementAt(offset) ?: return@suspendingReadAction null
            PsiTreeUtil.getParentOfType(element, jsFunctionClass as Class<out PsiElement>)
        } ?: return createErrorResult("No function found at line $line, column $column. Position the cursor on a function name.")

        val relativePath = ProjectUtils.getToolFilePath(project, virtualFile)

        val jsParameterInfoClass = try {
            Class.forName("com.intellij.lang.javascript.refactoring.changeSignature.JSParameterInfo")
        } catch (_: ClassNotFoundException) {
            null
        }

        return try {
            val (processor, affectedFiles) = suspendingReadAction {
                val currentName = jsFunction.javaClass.getMethod("getName").invoke(jsFunction) as? String ?: ""
                val targetName = newName ?: currentName

                val procCtor = jsChangeSignatureProcessorClass.constructors.firstOrNull { ctor ->
                    ctor.parameterCount >= 7 && ctor.parameterTypes[0].isAssignableFrom(jsFunction.javaClass)
                } ?: jsChangeSignatureProcessorClass.constructors.firstOrNull()
                ?: throw Exception("Cannot locate JSChangeSignatureProcessor constructor")

                val procArgs = arrayOfNulls<Any>(procCtor.parameterCount)
                procArgs[0] = jsFunction
                procArgs[1] = null // JSAttributeList.AccessType (optional/null)
                procArgs[2] = targetName
                procArgs[3] = newReturnType
                if (jsParameterInfoClass != null) {
                    val p5Ctor = jsParameterInfoClass.constructors.firstOrNull { c ->
                        c.parameterCount == 5 && c.parameterTypes[4] == Int::class.javaPrimitiveType
                    } ?: jsParameterInfoClass.constructors.firstOrNull { c -> c.parameterCount >= 5 }

                    val paramContainer = jsFunction.javaClass.methods.firstOrNull { it.name == "getParameterList" || it.name == "getParameters" }?.invoke(jsFunction)
                    val existingParams = if (paramContainer is Array<*>) paramContainer.toList() else if (paramContainer != null) {
                        val getParams = paramContainer.javaClass.methods.firstOrNull { it.name == "getParameters" }
                        (getParams?.invoke(paramContainer) as? Array<*>)?.toList() ?: emptyList()
                    } else emptyList()

                    val paramInfoList = mutableListOf<Any>()

                    if (newParametersJson != null && newParametersJson.isNotEmpty()) {
                        for (element in newParametersJson) {
                            val obj = element.jsonObject
                            val oldIdx = obj["oldIndex"]?.jsonPrimitive?.int ?: -1
                            val pName = obj["name"]?.jsonPrimitive?.content
                                ?: if (oldIdx in existingParams.indices) {
                                    existingParams[oldIdx]?.javaClass?.getMethod("getName")?.invoke(existingParams[oldIdx]) as? String ?: ""
                                } else ""
                            val pType = obj["type"]?.jsonPrimitive?.content ?: ""
                            val pDefault = obj["defaultValue"]?.jsonPrimitive?.content ?: ""

                            if (p5Ctor != null) {
                                val pObj = p5Ctor.newInstance(pName, pType, pDefault, "", oldIdx)
                                paramInfoList.add(pObj)
                            }
                        }
                    } else {
                        for ((idx, param) in existingParams.withIndex()) {
                            val paramName = param?.javaClass?.getMethod("getName")?.invoke(param) as? String ?: "arg$idx"
                            if (p5Ctor != null) {
                                val pObj = p5Ctor.newInstance(paramName, "", "", "", idx)
                                paramInfoList.add(pObj)
                            }
                        }
                    }

                    val arr = java.lang.reflect.Array.newInstance(jsParameterInfoClass, paramInfoList.size)
                    paramInfoList.forEachIndexed { idx, item -> java.lang.reflect.Array.set(arr, idx, item) }
                    procArgs[4] = arr
                } else {
                    procArgs[4] = null
                }
                procArgs[5] = emptySet<Any>()
                procArgs[6] = emptySet<Any>()
                if (procArgs.size > 7) procArgs[7] = false

                val proc = procCtor.newInstance(*procArgs) as com.intellij.refactoring.BaseRefactoringProcessor
                proc to relativePath
            }

            edtAction {
                processor.setPreviewUsages(false)
                val hook = processorRunHook
                if (hook != null) hook() else processor.run()
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveAllDocuments()
            }

            createJsonResult(ChangeSignatureResult(
                success = true,
                file = affectedFiles,
                message = "Changed JavaScript/TypeScript signature of function",
                affectedFiles = listOf(affectedFiles),
                changesCount = 1
            ))
        } catch (e: Throwable) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            createErrorResult("JavaScript/TypeScript change signature failed: ${cause.message}")
        }
    }

    private fun prepareChange(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        filePath: String,
        line: Int,
        column: Int
    ): Result<SignaturePreparation> {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return Result.failure(Exception("Cannot resolve PSI for: $filePath"))

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return Result.failure(Exception("Cannot get document for: $filePath"))

        if (line < 1 || line > document.lineCount) {
            return Result.failure(Exception("Line $line is out of range (file has ${document.lineCount} lines)"))
        }

        val offset = document.getLineStartOffset(line - 1) + (column - 1).coerceAtLeast(0)
        val element = psiFile.findElementAt(offset)
            ?: return Result.failure(Exception("No element found at line $line, column $column"))

        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            ?: return Result.failure(Exception("No method found at line $line, column $column. Position the cursor on a method name."))

        val relativePath = ProjectUtils.getToolFilePath(project, virtualFile)
        return Result.success(SignaturePreparation(method, relativePath))
    }

    private suspend fun applyChange(
        project: Project,
        prep: SignaturePreparation,
        newName: String?,
        newReturnType: String?,
        newVisibility: String?,
        newParametersJson: kotlinx.serialization.json.JsonArray?,
        generateDelegate: Boolean,
        changeSignatureProcessorClass: Class<*>,
        javaChangeInfoImplClass: Class<*>,
        parameterInfoImplClass: Class<*>
    ): CallToolResult {
        return try {
            val method = prep.method

            val (changeInfo, verification) = suspendingReadAction {
                val factory = JavaPsiFacade.getElementFactory(project)

                val effectiveName = newName ?: method.name
                val canonicalTypesClass = Class.forName("com.intellij.refactoring.util.CanonicalTypes")
                val createMethod = canonicalTypesClass.getMethod("createTypeWrapper", PsiType::class.java)

                val requestedReturnPsiType = newReturnType?.let { factory.createTypeFromText(it, method) }
                val effectiveReturnType = if (requestedReturnPsiType != null) {
                    createMethod.invoke(null, requestedReturnPsiType)
                } else {
                    if (method.returnType != null) createMethod.invoke(null, method.returnType) else null
                }

                val effectiveVisibility = when (newVisibility) {
                    "public" -> PsiModifier.PUBLIC
                    "protected" -> PsiModifier.PROTECTED
                    "private" -> PsiModifier.PRIVATE
                    "package-private", "package-local" -> PsiModifier.PACKAGE_LOCAL
                    else -> currentVisibility(method)
                }

                val paramInfos = if (newParametersJson != null) {
                    buildParameterInfos(method, newParametersJson, parameterInfoImplClass, factory)
                        .getOrThrow()
                } else {
                    buildCurrentParameterInfos(method, parameterInfoImplClass)
                }

                val changeInfoClass = Class.forName("com.intellij.refactoring.changeSignature.JavaChangeInfo")
                val thrownExceptionInfoClass = Class.forName("com.intellij.refactoring.changeSignature.ThrownExceptionInfo")
                val thrownExceptions = try {
                    val javaThrownExceptionInfoClass = Class.forName("com.intellij.refactoring.changeSignature.JavaThrownExceptionInfo")
                    val existingThrows = method.throwsList.referenceElements
                    if (existingThrows.isEmpty()) {
                        java.lang.reflect.Array.newInstance(thrownExceptionInfoClass, 0)
                    } else {
                        val arr = java.lang.reflect.Array.newInstance(thrownExceptionInfoClass, existingThrows.size)
                        for ((i, ref) in existingThrows.withIndex()) {
                            val psiType = ref.resolve()?.let { resolved ->
                                if (resolved is PsiClass) {
                                    JavaPsiFacade.getElementFactory(project).createType(resolved)
                                } else null
                            } ?: PsiElementFactory.getInstance(project).createTypeFromText(ref.qualifiedName, method)
                            val info = javaThrownExceptionInfoClass.getConstructor(Integer.TYPE, PsiType::class.java)
                                .newInstance(i, psiType)
                            java.lang.reflect.Array.set(arr, i, info)
                        }
                        arr
                    }
                } catch (e: Exception) {
                    LOG.warn("Could not preserve throws declarations, falling back to empty: ${e.message}")
                    java.lang.reflect.Array.newInstance(thrownExceptionInfoClass, 0)
                }
                val canonicalTypeClass = Class.forName("com.intellij.refactoring.util.CanonicalTypes\$Type")

                val constructor = javaChangeInfoImplClass.getConstructor(
                    String::class.java,
                    PsiMethod::class.java,
                    String::class.java,
                    canonicalTypeClass,
                    paramInfos.javaClass,
                    thrownExceptions.javaClass,
                    Boolean::class.java,
                    Set::class.java,
                    Set::class.java
                )

                val newChangeInfo = constructor.newInstance(
                    effectiveVisibility,
                    method,
                    effectiveName,
                    effectiveReturnType,
                    paramInfos,
                    thrownExceptions,
                    generateDelegate,
                    emptySet<PsiMethod>(),
                    emptySet<PsiMethod>()
                )

                newChangeInfo to SignatureVerification(
                    pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method),
                    before = captureSignatureState(method),
                    targetName = newName,
                    targetReturnTypeText = requestedReturnPsiType?.canonicalText,
                    targetVisibility = if (newVisibility != null) effectiveVisibility else null,
                    targetParameters = newParametersJson?.map { paramJson ->
                        val obj = paramJson.jsonObject
                        val paramName = obj["name"]!!.jsonPrimitive.content
                        val paramType = factory.createTypeFromText(obj["type"]!!.jsonPrimitive.content, method)
                        paramName to paramType.canonicalText
                    }
                )
            }

            val changeInfoClass = Class.forName("com.intellij.refactoring.changeSignature.JavaChangeInfo")
            val affectedFiles = mutableSetOf<String>()

            edtAction {
                val docManager = FileDocumentManager.getInstance()
                val unsavedBefore = docManager.unsavedDocuments.toSet()

                val processor = changeSignatureProcessorClass
                    .getConstructor(Project::class.java, changeInfoClass)
                    .newInstance(project, changeInfo) as com.intellij.refactoring.BaseRefactoringProcessor

                processor.setPreviewUsages(false)
                val hook = processorRunHook
                if (hook != null) hook() else processor.run()

                PsiDocumentManager.getInstance(project).commitAllDocuments()

                val unsavedAfter = docManager.unsavedDocuments.toSet()
                val changedDocs = unsavedAfter - unsavedBefore
                for (doc in changedDocs) {
                    val vf = docManager.getFile(doc)
                    if (vf != null) {
                        affectedFiles.add(ProjectUtils.getToolFilePath(project, vf))
                    }
                }
                affectedFiles.add(prep.relativePath)

                docManager.saveAllDocuments()
            }

            val requestedChangeApplied = suspendingReadAction {
                anyRequestedAspectApplied(verification)
            }

            if (!requestedChangeApplied) {
                createErrorResult(
                    "Change signature did not apply — the IDE aborted the refactoring " +
                        "(read-only file, unwritable elements, or indexing in progress)."
                )
            } else {
                createJsonResult(ChangeSignatureResult(
                    success = true,
                    file = prep.relativePath,
                    message = "Changed signature of '${method.name}'",
                    affectedFiles = affectedFiles.toList(),
                    changesCount = affectedFiles.size
                ))
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            createErrorResult("Change signature failed: ${cause.message}")
        }
    }

    private fun buildParameterInfos(
        method: PsiMethod,
        parametersJson: kotlinx.serialization.json.JsonArray,
        parameterInfoImplClass: Class<*>,
        factory: PsiElementFactory
    ): Result<Any> {
        val infos = mutableListOf<Any>()

        for (paramJson in parametersJson) {
            val obj = paramJson.jsonObject
            val oldIndex = obj["oldIndex"]?.jsonPrimitive?.int
                ?: return Result.failure(Exception("Each parameter must have 'oldIndex' (int, -1 for new)"))
            val name = obj["name"]?.jsonPrimitive?.content
                ?: return Result.failure(Exception("Each parameter must have 'name'"))
            val typeStr = obj["type"]?.jsonPrimitive?.content
                ?: return Result.failure(Exception("Each parameter must have 'type'"))
            val defaultValue = obj["defaultValue"]?.jsonPrimitive?.content ?: ""

            val type = try {
                factory.createTypeFromText(typeStr, method)
            } catch (e: Exception) {
                return Result.failure(Exception("Invalid parameter type: '$typeStr'. ${e.message}"))
            }

            val info = parameterInfoImplClass.getConstructor(
                Int::class.java, String::class.java, PsiType::class.java, String::class.java
            ).newInstance(oldIndex, name, type, defaultValue)

            infos.add(info)
        }

        val array = java.lang.reflect.Array.newInstance(parameterInfoImplClass, infos.size)
        infos.forEachIndexed { i, info -> java.lang.reflect.Array.set(array, i, info) }
        return Result.success(array)
    }

    private fun currentVisibility(method: PsiMethod): String = when {
        method.hasModifierProperty(PsiModifier.PUBLIC) -> PsiModifier.PUBLIC
        method.hasModifierProperty(PsiModifier.PROTECTED) -> PsiModifier.PROTECTED
        method.hasModifierProperty(PsiModifier.PRIVATE) -> PsiModifier.PRIVATE
        else -> PsiModifier.PACKAGE_LOCAL
    }

    private fun captureSignatureState(method: PsiMethod): SignatureState = SignatureState(
        name = method.name,
        returnTypeText = method.returnType?.canonicalText,
        visibility = currentVisibility(method),
        parameters = method.parameterList.parameters.map { it.name to it.type.canonicalText }
    )

    private fun anyRequestedAspectApplied(verification: SignatureVerification): Boolean {
        val method = verification.pointer.element ?: return true
        val after = captureSignatureState(method)
        val before = verification.before
        val aspects = listOfNotNull(
            verification.targetName?.let {
                after.name == it || after.name != before.name
            },
            verification.targetReturnTypeText?.let {
                after.returnTypeText == it || after.returnTypeText != before.returnTypeText
            },
            verification.targetVisibility?.let {
                after.visibility == it || after.visibility != before.visibility
            },
            verification.targetParameters?.let {
                after.parameters == it || after.parameters != before.parameters
            }
        )
        return aspects.isEmpty() || aspects.any { it }
    }

    private fun buildCurrentParameterInfos(method: PsiMethod, parameterInfoImplClass: Class<*>): Any {
        val params = method.parameterList.parameters
        val array = java.lang.reflect.Array.newInstance(parameterInfoImplClass, params.size)
        params.forEachIndexed { i, param ->
            val info = parameterInfoImplClass.getConstructor(
                Int::class.java, String::class.java, PsiType::class.java, String::class.java
            ).newInstance(i, param.name, param.type, "")
            java.lang.reflect.Array.set(array, i, info)
        }
        return array
    }
}
