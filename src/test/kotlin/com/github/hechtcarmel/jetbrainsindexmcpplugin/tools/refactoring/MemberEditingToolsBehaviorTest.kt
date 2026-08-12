package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FileStructureTool
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path

class MemberEditingToolsBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads off disk, so the assertions also prove the tool flushed its document. */
    private fun readProjectFile(relativePath: String): String =
        Files.readString(Path.of(requireNotNull(project.basePath), relativePath))

    private fun parseResult(result: CallToolResult): MemberEditResult =
        json.decodeFromString(toolText(result))

    private fun parseErrorResult(result: CallToolResult): MemberErrorResult =
        json.decodeFromString(toolText(result))

    // ── Java: ide_replace_member ──

    fun testJavaReplaceMethodBody() = runBlocking {
        writeProjectFile("src/Calculator.java", """
            public class Calculator {
                public int add(int a, int b) {
                    return a + b;
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Calculator.java")
            put("class", "Calculator")
            put("member", "add")
            put("content", "\n        return a + b + 1;\n    ")
        })

        assertToolSucceeded("Replace body should succeed", result)
        val payload = parseResult(result)
        assertTrue(payload.success)

        val content = readProjectFile("src/Calculator.java")
        assertTrue("File should contain new body", content.contains("a + b + 1"))
        assertTrue("File should still have method signature", content.contains("public int add(int a, int b)"))
    }

    fun testJavaReplaceFieldInitializer() = runBlocking {
        writeProjectFile("src/Config.java", """
            public class Config {
                private int timeout = 30;
                public String getName() { return "config"; }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Config.java")
            put("class", "Config")
            put("member", "timeout")
            put("content", "60")
        })

        assertToolSucceeded("Replace field initializer should succeed", result)
        val content = readProjectFile("src/Config.java")
        assertTrue("Field should have new value", content.contains("60"))
    }

    fun testJavaReplaceMemberNotFound() = runBlocking {
        writeProjectFile("src/Empty.java", """
            public class Empty {
                public void doWork() {}
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Empty.java")
            put("class", "Empty")
            put("member", "nonExistent")
            put("content", "return;")
        })

        val payload = parseErrorResult(result)
        assertEquals("member_not_found", payload.error)
        assertEquals("nonExistent", payload.member)
        assertEquals("Member 'nonExistent' not found in the specified scope.", payload.hint)
    }

    fun testJavaReplaceOverloadedMethodDisambiguatesByParameterCount() = runBlocking {
        writeProjectFile("src/Overloaded.java", """
            public class Overloaded {
                public void process(String s) {
                    System.out.println(s);
                }
                public void process(String s, int n) {
                    System.out.println(s + n);
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Overloaded.java")
            put("class", "Overloaded")
            put("member", "process")
            put("parameterCount", 1)
            put("content", "\n        System.out.println(\"replaced\");\n    ")
        })

        assertToolSucceeded("Disambiguated replace should succeed", result)
        val content = readProjectFile("src/Overloaded.java")
        assertTrue("Single-param method should be replaced", content.contains("replaced"))
        assertTrue("Two-param method should be unchanged", content.contains("s + n"))
    }

    fun testJavaReplaceOverloadedMethodReturnsAmbiguousError() = runBlocking {
        writeProjectFile("src/Ambiguous.java", """
            public class Ambiguous {
                public void run(String s) {}
                public void run(int n) {}
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Ambiguous.java")
            put("class", "Ambiguous")
            put("member", "run")
            put("content", "return;")
        })

        val payload = parseErrorResult(result)
        assertEquals("ambiguous_member", payload.error)
        assertEquals("run", payload.member)
        assertEquals("Specify parameterCount or line to disambiguate.", payload.hint)
        assertEquals("Both overloads should be offered", 2, payload.candidates?.size ?: 0)
    }

    // ── Java: ide_edit_member ──

    fun testJavaEditMemberReplacesEntireDeclaration() = runBlocking {
        writeProjectFile("src/Service.java", """
            public class Service {
                public String getName() {
                    return "old";
                }
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/Service.java")
            put("class", "Service")
            put("member", "getName")
            put("content", "public String getFullName() {\n        return \"new\";\n    }")
        })

        assertToolSucceeded("Edit member should succeed", result)
        val content = readProjectFile("src/Service.java")
        assertTrue("Should have new method name", content.contains("getFullName"))
        assertFalse("Old method name should be gone", content.contains("getName"))
        assertTrue("Should have new body", content.contains("\"new\""))
    }

    // ── Java: ide_insert_member ──

    fun testJavaInsertMemberAtEnd() = runBlocking {
        writeProjectFile("src/Base.java", """
            public class Base {
                public void existing() {}
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/Base.java")
            put("class", "Base")
            put("content", "public void newMethod() {\n        System.out.println(\"inserted\");\n    }")
        })

        assertToolSucceeded("Insert should succeed", result)
        val content = readProjectFile("src/Base.java")
        assertTrue("Should contain new method", content.contains("newMethod"))
        assertTrue("Should still contain existing method", content.contains("existing"))
    }

    fun testJavaInsertMemberBeforeAnchor() = runBlocking {
        writeProjectFile("src/Ordered.java", """
            public class Ordered {
                public void alpha() {}
                public void gamma() {}
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/Ordered.java")
            put("class", "Ordered")
            put("content", "public void beta() {}")
            put("position", "before")
            put("anchor", "gamma")
        })

        assertToolSucceeded("Insert before should succeed", result)
        val content = readProjectFile("src/Ordered.java")
        assertTrue("Should contain beta", content.contains("beta"))
        val betaPos = content.indexOf("beta")
        val gammaPos = content.indexOf("gamma")
        assertTrue("beta should appear before gamma", betaPos < gammaPos)
    }

    fun testJavaInsertMemberAfterAnchor() = runBlocking {
        writeProjectFile("src/AfterTest.java", """
            public class AfterTest {
                public void first() {}
                public void third() {}
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/AfterTest.java")
            put("class", "AfterTest")
            put("content", "public void second() {}")
            put("position", "after")
            put("anchor", "first")
        })

        assertToolSucceeded("Insert after should succeed", result)
        val content = readProjectFile("src/AfterTest.java")
        val secondPos = content.indexOf("second")
        val firstPos = content.indexOf("first")
        val thirdPos = content.indexOf("third")
        assertTrue("second should appear after first", secondPos > firstPos)
        assertTrue("second should appear before third", secondPos < thirdPos)
    }

    fun testJavaInsertMemberFirstPositionLandsBeforeExistingMembers() = runBlocking {
        writeProjectFile("src/FirstPos.java", """
            public class FirstPos {
                public void existing() {}
                public void trailing() {}
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/FirstPos.java")
            put("class", "FirstPos")
            put("content", "public void opener() {}")
            put("position", "first")
        })

        assertToolSucceeded("Insert at position 'first' should succeed", result)
        val content = readProjectFile("src/FirstPos.java")
        val openerPos = content.indexOf("opener")
        val existingPos = content.indexOf("existing")
        val trailingPos = content.indexOf("trailing")
        assertTrue("opener must be present", openerPos >= 0)
        assertTrue("opener must be inside the class body", openerPos > content.indexOf("{"))
        assertTrue("opener must precede existing", openerPos < existingPos)
        assertTrue("opener must precede trailing", openerPos < trailingPos)
    }

    // ── Java: ide_insert_member stale-preparation guards ──
    // The read-prepare/write-apply gap cannot be reproduced through execute() alone, so these
    // drive applyInsertion directly with a preparation that went stale in between.

    fun testInsertMemberStaleOffsetBeyondDocumentReturnsRetryError() = runBlocking {
        writeProjectFile("src/StaleOffset.java", """
            public class StaleOffset {
                public void existing() {
                    System.out.println("plenty of content to make the document long");
                }
            }
        """.trimIndent())

        val basePath = requireNotNull(project.basePath)
        val virtualFile = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/src/StaleOffset.java")
        )
        val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
        val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(psiFile))
        val staleOffset = document.textLength

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("class StaleOffset {}")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }

        val prep = InsertPreparation(psiFile, document, staleOffset, "src/StaleOffset.java")
        val result = InsertMemberTool().applyInsertion(project, prep, "public void late() {}", reformat = false)

        assertToolFailed("Stale offset must not be applied", result)
        val text = toolText(result)
        assertTrue("Error should report the out-of-bounds offset, got: $text", text.contains("out of bounds"))
        assertTrue("Error should suggest retrying, got: $text", text.contains("retry the operation"))
        assertEquals("Document must be left untouched", "class StaleOffset {}", document.text)
    }

    fun testInsertMemberInvalidPsiFileReturnsRetryError() = runBlocking {
        writeProjectFile("src/Vanishing.java", """
            public class Vanishing {
                public void existing() {}
            }
        """.trimIndent())

        val basePath = requireNotNull(project.basePath)
        val virtualFile = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/src/Vanishing.java")
        )
        val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
        val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(psiFile))
        val offset = document.textLength

        WriteCommandAction.runWriteCommandAction(project) {
            virtualFile.delete(this)
        }
        assertFalse("Precondition: PSI file must be invalid after deletion", psiFile.isValid)

        val prep = InsertPreparation(psiFile, document, offset, "src/Vanishing.java")
        val result = InsertMemberTool().applyInsertion(project, prep, "public void late() {}", reformat = false)

        assertToolFailed("Invalid PSI file must not be edited", result)
        val text = toolText(result)
        assertTrue("Error should say the file is no longer valid, got: $text", text.contains("no longer valid"))
        assertTrue("Error should suggest retrying, got: $text", text.contains("retry the operation"))
    }

    // ── Java: ide_edit_member replaces large method with short one without IndexOutOfBoundsException ──

    fun testEditMemberShorterReplacementDoesNotThrow() = runBlocking {
        val longBody = (1..20).joinToString("\n") { "                    System.out.println(\"line$it\");" }
        writeProjectFile("src/Shrink.java",
            "public class Shrink {\n    public void verbose() {\n$longBody\n    }\n}\n")

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/Shrink.java")
            put("class", "Shrink")
            put("member", "verbose")
            put("content", "public void verbose() { }")
        })

        assertToolSucceeded("Edit should succeed without IndexOutOfBoundsException", result)
        val parsed = parseResult(result)
        assertTrue("startLine must be positive", parsed.startLine!! > 0)
        assertTrue("endLine must be >= startLine", parsed.endLine!! >= parsed.startLine!!)
        assertTrue("endLine must not exceed document line count", parsed.endLine!! <= 10)
    }

    // ── Java: ide_file_structure endLine ──

    fun testJavaFileStructureIncludesEndLine() = runBlocking {
        LanguageHandlerRegistry.registerHandlers()
        Assume.assumeTrue(
            "No file-structure handlers available in this sandbox",
            LanguageHandlerRegistry.hasStructureHandlers()
        )

        writeProjectFile("src/Structured.java", """
            public class Structured {
                private int count = 0;

                public void longMethod() {
                    int a = 1;
                    int b = 2;
                    int c = a + b;
                    System.out.println(c);
                }

                public void shortMethod() {
                    return;
                }
            }
        """.trimIndent())

        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", "src/Structured.java")
        })

        assertToolSucceeded("File structure should succeed", result)
        val structure = json.decodeFromString<FileStructureResult>(toolText(result)).structure

        val classLine = structure.lines().single { it.contains("Structured") && it.contains("class") }
        assertTrue("Class should span the whole file, got: $classLine", classLine.endsWith("(lines 1-14)"))

        val longMethodLine = structure.lines().single { it.contains("longMethod") }
        assertTrue("longMethod should span its body, got: $longMethodLine", longMethodLine.endsWith("(lines 4-9)"))

        val shortMethodLine = structure.lines().single { it.contains("shortMethod") }
        assertTrue("shortMethod should span its body, got: $shortMethodLine", shortMethodLine.endsWith("(lines 11-13)"))

        val fieldLine = structure.lines().single { it.contains("count") }
        assertTrue("Single-line field should report one line, got: $fieldLine", fieldLine.endsWith("(line 2)"))
    }

    // ── Java: error cases ──

    fun testClassNotFoundReturnsError() = runBlocking {
        writeProjectFile("src/Solo.java", """
            public class Solo {
                public void method() {}
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Solo.java")
            put("class", "NonExistent")
            put("member", "method")
            put("content", "return;")
        })

        assertToolFailed("Should fail for missing class", result)
        assertEquals("Class 'NonExistent' not found in file.", toolText(result))
    }

    fun testAbstractMethodHasNoBodyToReplace() = runBlocking {
        writeProjectFile("src/AbstractService.java", """
            public abstract class AbstractService {
                public abstract void process();
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/AbstractService.java")
            put("class", "AbstractService")
            put("member", "process")
            put("content", "System.out.println(\"hi\");")
        })

        assertToolFailed("Abstract method has no body to replace", result)
        assertEquals(
            "Member 'process' has no body/initializer to replace. Use ide_edit_member for full replacement.",
            toolText(result)
        )
        assertFileDoesNotContain("src/AbstractService.java", "System.out.println")
    }

    fun testFileNotFoundReturnsError() = runBlocking {
        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/DoesNotExist.java")
            put("class", "Foo")
            put("member", "bar")
            put("content", "return;")
        })

        assertToolFailed("Should fail for missing file", result)
        assertEquals("File not found: src/DoesNotExist.java", toolText(result))
    }

    // ── Java: insert without class specified ──

    fun testJavaInsertWithoutClassInfersSingleClass() = runBlocking {
        writeProjectFile("src/SingleClass.java", """
            public class SingleClass {
                public void existing() {}
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/SingleClass.java")
            put("content", "public void added() {}")
        })

        assertToolSucceeded("Insert without class should succeed for single-class file", result)
        val content = readProjectFile("src/SingleClass.java")
        assertTrue("Method should be inside the class", content.contains("added"))
        val addedPos = content.indexOf("added")
        val closingBrace = content.lastIndexOf("}")
        assertTrue("Method should be before the class closing brace", addedPos < closingBrace)
    }

    fun testJavaInsertWithoutClassAndMultipleClassesFails() = runBlocking {
        writeProjectFile("src/MultiClass.java", """
            class First {}
            class Second {}
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/MultiClass.java")
            put("content", "public void ambiguous() {}")
        })

        assertToolFailed("Should fail for ambiguous class scope", result)
        assertEquals(
            "Cannot determine insertion point. The file may have multiple classes — specify the 'class' parameter.",
            toolText(result)
        )
        assertFileDoesNotContain("src/MultiClass.java", "ambiguous")
    }

    // ── Java: class/interface declaration editing ──

    fun testJavaEditClassDeclarationAddsTypeParameter() = runBlocking {
        writeProjectFile("src/GenericTarget.java", """
            public interface GenericTarget {
                void process();
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/GenericTarget.java")
            put("class", "GenericTarget")
            put("member", "GenericTarget")
            put("content", "public interface GenericTarget<T> {\n    T process();\n}")
        })

        assertToolSucceeded("Edit class declaration should succeed", result)
        val content = readProjectFile("src/GenericTarget.java")
        assertTrue("Should have type parameter", content.contains("GenericTarget<T>"))
        assertTrue("Should have updated method", content.contains("T process()"))
    }

    fun testJavaEditClassDeclarationChangesImplements() = runBlocking {
        writeProjectFile("src/ImplTarget.java", """
            public class ImplTarget {
                public void run() {}
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/ImplTarget.java")
            put("class", "ImplTarget")
            put("member", "ImplTarget")
            put("content", "public class ImplTarget implements Runnable {\n    public void run() {}\n}")
        })

        assertToolSucceeded("Edit class implements should succeed", result)
        val content = readProjectFile("src/ImplTarget.java")
        assertTrue("Should have implements", content.contains("implements Runnable"))
    }

    fun testJavaEditTopLevelClassWithoutClassParam() = runBlocking {
        writeProjectFile("src/TopLevel.java", """
            public class TopLevel {
                public void method() {}
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/TopLevel.java")
            put("member", "TopLevel")
            put("content", "public abstract class TopLevel {\n    public abstract void method();\n}")
        })

        assertToolSucceeded("Edit top-level class without class param should succeed", result)
        val content = readProjectFile("src/TopLevel.java")
        assertTrue("Should be abstract", content.contains("abstract class TopLevel"))
    }

    // ── Java: record declaration editing ──

    fun testJavaEditRecordDeclaration() = runBlocking {
        writeProjectFile("src/RecordTarget.java", """
            public record RecordTarget(String name, int age) {
                public String displayName() {
                    return name;
                }
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/RecordTarget.java")
            put("class", "RecordTarget")
            put("member", "RecordTarget")
            put("content", "public record RecordTarget(String name, int age, String email) implements Serializable {\n    public String displayName() {\n        return name + \" <\" + email + \">\";\n    }\n}")
        })

        assertToolSucceeded("Edit record declaration should succeed", result)
        val content = readProjectFile("src/RecordTarget.java")
        assertTrue("Should have new component", content.contains("String email"))
        assertTrue("Should have implements", content.contains("implements Serializable"))
        assertTrue("Should have updated method", content.contains("email"))
        assertFalse("Should not have nested record", content.contains("record RecordTarget(String name, int age) {"))
    }

    // ── Java: static initializer block ──

    fun testJavaReplaceStaticInitializerBody() = runBlocking {
        writeProjectFile("src/WithStaticInit.java", """
            public class WithStaticInit {
                private static int value;
                static {
                    value = 42;
                }
                public static int getValue() { return value; }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/WithStaticInit.java")
            put("class", "WithStaticInit")
            put("member", "static")
            put("content", "\n        value = 99;\n    ")
        })

        assertToolSucceeded("Replace static init body should succeed", result)
        val content = readProjectFile("src/WithStaticInit.java")
        assertTrue("Should contain new value", content.contains("99"))
        assertFalse("Old value should be gone", content.contains("42"))
    }

    fun testJavaEditStaticInitializerFull() = runBlocking {
        writeProjectFile("src/WithStaticInit2.java", """
            public class WithStaticInit2 {
                private static String label;
                static {
                    label = "old";
                }
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/WithStaticInit2.java")
            put("class", "WithStaticInit2")
            put("member", "static")
            put("content", "static {\n        label = \"new\";\n        System.out.println(label);\n    }")
        })

        assertToolSucceeded("Edit static init should succeed", result)
        val content = readProjectFile("src/WithStaticInit2.java")
        assertTrue("Should contain new body", content.contains("\"new\""))
        assertTrue("Should contain println", content.contains("System.out.println"))
    }

    // ── Java: auto-import after edit ──

    fun testJavaEditMemberWithReformatDoesNotCrash() = runBlocking {
        writeProjectFile("src/ImportTest.java", """
            import java.util.List;
            import java.util.Map;

            public class ImportTest {
                public List<String> getItems() {
                    return null;
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/ImportTest.java")
            put("class", "ImportTest")
            put("member", "getItems")
            put("content", "\n        return null;\n    ")
            put("reformat", true)
        })

        assertToolSucceeded("Replace with reformat+import optimization should succeed", result)
        val payload = parseResult(result)
        assertTrue(payload.success)
    }

    // ── Python tests ──

    fun testPythonReplaceMethodBody() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.python.isAvailable) return@runBlocking Unit

        writeProjectFile("src/calc.py", """
            class Calculator:
                def add(self, a, b):
                    return a + b
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/calc.py")
            put("class", "Calculator")
            put("member", "add")
            put("content", "return a + b + 1")
        })

        assertToolSucceeded("Python replace body should succeed", result)
        val content = readProjectFile("src/calc.py")
        assertTrue("Should contain new body", content.contains("a + b + 1"))
    }

    fun testPythonReplaceTopLevelFunctionBody() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.python.isAvailable) return@runBlocking Unit

        writeProjectFile("src/util.py", """
            def greet(name):
                return f"Hello {name}"
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/util.py")
            put("member", "greet")
            put("content", "return f\"Hi {name}\"")
        })

        assertToolSucceeded("Python top-level function replace body should succeed", result)
        val content = readProjectFile("src/util.py")
        assertTrue("Should contain new body", content.contains("Hi {name}"))
    }

    fun testPythonReplaceFieldInitializer() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.python.isAvailable) return@runBlocking Unit

        writeProjectFile("src/config.py", """
            TIMEOUT = 30
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/config.py")
            put("member", "TIMEOUT")
            put("content", "60")
        })

        assertToolSucceeded("Python replace field initializer should succeed", result)
        val content = readProjectFile("src/config.py")
        assertTrue("Field should have new value", content.contains("60"))
    }

    fun testPythonEditMemberReplacesEntireDeclaration() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.python.isAvailable) return@runBlocking Unit

        writeProjectFile("src/service.py", """
            class Service:
                def get_name(self):
                    return "old"
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/service.py")
            put("class", "Service")
            put("member", "get_name")
            put("content", "def get_full_name(self):\n        return \"new\"")
        })

        assertToolSucceeded("Python edit member should succeed", result)
        val content = readProjectFile("src/service.py")
        assertTrue("Should contain get_full_name", content.contains("get_full_name"))
        assertFalse("Old function should be gone", content.contains("get_name"))
    }

    fun testPythonInsertMemberAtEnd() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.python.isAvailable) return@runBlocking Unit

        writeProjectFile("src/base.py", """
            class Base:
                def existing(self):
                    pass
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/base.py")
            put("class", "Base")
            put("content", "def new_method(self):\n        pass")
        })

        assertToolSucceeded("Python insert member should succeed", result)
        val content = readProjectFile("src/base.py")
        assertTrue("Should contain new method", content.contains("new_method"))
    }

    fun testPythonInsertTopLevelFunction() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.python.isAvailable) return@runBlocking Unit

        writeProjectFile("src/top.py", """
            def first():
                pass
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/top.py")
            put("content", "def second():\n    pass")
        })

        assertToolSucceeded("Python insert top level function should succeed", result)
        val content = readProjectFile("src/top.py")
        assertTrue("Should contain second", content.contains("second"))
    }

    fun testPythonMemberNotFound() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.python.isAvailable) return@runBlocking Unit

        writeProjectFile("src/empty.py", """
            def work():
                pass
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/empty.py")
            put("member", "nonExistent")
            put("content", "pass")
        })

        val payload = parseErrorResult(result)
        assertEquals("member_not_found", payload.error)
    }

    // ── JS/TS tests ──

    fun testJsReplaceMethodBody() = runBlocking {
        Assume.assumeTrue("JavaScript plugin not available", com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.javaScript.isAvailable)

        writeProjectFile("src/calc.js", """
            class Calculator {
                add(a, b) {
                    return a + b;
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/calc.js")
            put("class", "Calculator")
            put("member", "add")
            put("content", "return a + b + 1;")
        })

        assertToolSucceeded("JS replace body should succeed", result)
        val content = readProjectFile("src/calc.js")
        assertTrue("Should contain new body", content.contains("a + b + 1"))
    }

    fun testJsReplaceArrowFunctionBody() = runBlocking {
        Assume.assumeTrue("JavaScript plugin not available", com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.javaScript.isAvailable)

        writeProjectFile("src/arrow.js", """
            const add = (a, b) => {
                return a + b;
            };
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/arrow.js")
            put("member", "add")
            put("content", "return a + b + 2;")
        })

        assertToolSucceeded("JS arrow fn replace body should succeed", result)
        val content = readProjectFile("src/arrow.js")
        assertTrue("Should contain new body", content.contains("a + b + 2"))
    }

    fun testJsReplaceFieldInitializer() = runBlocking {
        Assume.assumeTrue("JavaScript plugin not available", com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.javaScript.isAvailable)

        writeProjectFile("src/config.js", """
            const TIMEOUT = 30;
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/config.js")
            put("member", "TIMEOUT")
            put("content", "60")
        })

        assertToolSucceeded("JS replace field initializer should succeed", result)
        val content = readProjectFile("src/config.js")
        assertTrue("Should contain new value", content.contains("60"))
    }

    fun testJsEditMemberReplacesEntireDeclaration() = runBlocking {
        Assume.assumeTrue("JavaScript plugin not available", com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.javaScript.isAvailable)

        writeProjectFile("src/service.js", """
            class Service {
                getName() {
                    return "old";
                }
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/service.js")
            put("class", "Service")
            put("member", "getName")
            put("content", "getFullName() {\n        return \"new\";\n    }")
        })

        assertToolSucceeded("JS edit member should succeed", result)
        val content = readProjectFile("src/service.js")
        assertTrue("Should contain getFullName", content.contains("getFullName"))
        assertFalse("Old method should be gone", content.contains("getName"))
    }

    fun testJsInsertMemberAtEnd() = runBlocking {
        Assume.assumeTrue("JavaScript plugin not available", com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.javaScript.isAvailable)

        writeProjectFile("src/base.js", """
            class Base {
                existing() {}
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/base.js")
            put("class", "Base")
            put("content", "newMethod() {}")
        })

        assertToolSucceeded("JS insert member should succeed", result)
        val content = readProjectFile("src/base.js")
        assertTrue("Should contain newMethod", content.contains("newMethod"))
    }

    fun testJsInsertTopLevelFunction() = runBlocking {
        Assume.assumeTrue("JavaScript plugin not available", com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.javaScript.isAvailable)

        writeProjectFile("src/top.js", """
            function first() {}
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/top.js")
            put("content", "function second() {}")
        })

        assertToolSucceeded("JS insert top level function should succeed", result)
        val content = readProjectFile("src/top.js")
        assertTrue("Should contain second", content.contains("second"))
    }

    fun testTsReplaceMethodBody() = runBlocking {
        Assume.assumeTrue("JavaScript plugin not available", com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.javaScript.isAvailable)

        writeProjectFile("src/calc.ts", """
            class Calculator {
                add(a: number, b: number): number {
                    return a + b;
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/calc.ts")
            put("class", "Calculator")
            put("member", "add")
            put("content", "return a + b + 1;")
        })

        assertToolSucceeded("TS replace method body should succeed", result)
        val content = readProjectFile("src/calc.ts")
        assertTrue("Should contain new body", content.contains("a + b + 1"))
    }

    fun testTsReplaceMethodWithGenerics() = runBlocking {
        Assume.assumeTrue("JavaScript plugin not available", com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.javaScript.isAvailable)

        writeProjectFile("src/gen.ts", """
            class Container<T> {
                getValue<U>(x: U): U {
                    return x;
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/gen.ts")
            put("class", "Container")
            put("member", "getValue")
            put("content", "console.log(x); return x;")
        })

        assertToolSucceeded("TS generic method replace body should succeed", result)
        val content = readProjectFile("src/gen.ts")
        assertTrue("Should contain console.log", content.contains("console.log"))
    }

    fun testJsMemberNotFound() = runBlocking {
        Assume.assumeTrue("JavaScript plugin not available", com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.javaScript.isAvailable)

        writeProjectFile("src/empty.js", """
            function work() {}
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/empty.js")
            put("member", "nonExistent")
            put("content", "return;")
        })

        val payload = parseErrorResult(result)
        assertEquals("member_not_found", payload.error)
    }

    // ── Go: Member Editing ──

    fun testGoReplaceMethodBody() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.go.isAvailable) return@runBlocking Unit

        writeProjectFile("src/calc.go", """
            package main

            type Calculator struct{}

            func (c *Calculator) Add(a int, b int) int {
                return a + b
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/calc.go")
            put("class", "Calculator")
            put("member", "Add")
            put("content", "return a + b + 1")
        })

        assertToolSucceeded("Go replace method body should succeed", result)
        val content = readProjectFile("src/calc.go")
        assertTrue("Go method body should be replaced", content.contains("a + b + 1"))
    }

    fun testGoInsertTopLevelFunction() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.go.isAvailable) return@runBlocking Unit

        writeProjectFile("src/main.go", """
            package main

            func main() {}
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/main.go")
            put("content", "func Helper() string { return \"ok\" }")
        })

        assertToolSucceeded("Go insert top-level function should succeed", result)
        val content = readProjectFile("src/main.go")
        assertTrue("Go file should contain inserted function", content.contains("Helper() string"))
    }

    // ── PHP: Member Editing ──

    fun testPhpReplaceMethodBody() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.php.isAvailable) return@runBlocking Unit

        writeProjectFile("src/Calculator.php", """
            <?php
            class Calculator {
                public function add(${'$'}a, ${'$'}b) {
                    return ${'$'}a + ${'$'}b;
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Calculator.php")
            put("class", "Calculator")
            put("member", "add")
            put("content", "return ${'$'}a + ${'$'}b + 1;")
        })

        assertToolSucceeded("PHP replace method body should succeed", result)
        val content = readProjectFile("src/Calculator.php")
        assertTrue("PHP method body should be replaced", content.contains("${'$'}a + ${'$'}b + 1"))
    }

    fun testPhpInsertClassMethod() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.php.isAvailable) return@runBlocking Unit

        writeProjectFile("src/User.php", """
            <?php
            class User {
                public function getName() { return "Alice"; }
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/User.php")
            put("class", "User")
            put("content", "public function getAge() { return 30; }")
        })

        assertToolSucceeded("PHP insert class method should succeed", result)
        val content = readProjectFile("src/User.php")
        assertTrue("PHP class should contain inserted method", content.contains("getAge()"))
    }

    // ── Rust: Member Editing ──

    fun testRustReplaceFunctionBody() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.rust.isAvailable) return@runBlocking Unit

        writeProjectFile("src/lib.rs", """
            pub fn add(a: i32, b: i32) -> i32 {
                a + b
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/lib.rs")
            put("member", "add")
            put("content", "a + b + 1")
        })

        assertToolSucceeded("Rust replace function body should succeed", result)
        val content = readProjectFile("src/lib.rs")
        assertTrue("Rust function body should be replaced", content.contains("a + b + 1"))
    }

    fun testRustInsertImplFunction() = runBlocking {
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.rust.isAvailable) return@runBlocking Unit

        writeProjectFile("src/main.rs", """
            struct MyStruct;
            impl MyStruct {
                pub fn new() -> Self { MyStruct }
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/main.rs")
            put("class", "MyStruct")
            put("content", "pub fn do_something(&self) {}")
        })

        assertToolSucceeded("Rust insert impl function should succeed", result)
        val content = readProjectFile("src/main.rs")
        assertTrue("Rust impl block should contain inserted method", content.contains("do_something"))
    }
}

