package net.spartanb312.grunteon.obfuscator

import net.spartanb312.grunteon.obfuscator.process.GlobalConfig
import net.spartanb312.grunteon.obfuscator.process.ObfConfig
import net.spartanb312.grunteon.obfuscator.process.TransformerEntry
import net.spartanb312.grunteon.obfuscator.process.transformers.optimize.MethodInliner
import net.spartanb312.grunteon.obfuscator.util.ClearClassNode
import net.spartanb312.grunteon.testcase.methodinline.Basic
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceMethodVisitor
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MethodInlinerTest {

    @Test
    fun inlinesSmallSameClassMethods() {
        val instance = readTestClasses(
            Basic::class.java,
            ObfConfig(
                globalConfig = GlobalConfig(
                    output = null
                ),
                transformers = listOf(
                    TransformerEntry(
                        config = MethodInliner.Config(maxInstructions = 8)
                    )
                )
            )
        )
        context(instance.workRes, instance) {
            instance.execute()
        }

        val classNode = instance.workRes.inputClassMap[CLASS_NAME]
            ?: error("Missing test class $CLASS_NAME")
        val runMethod = classNode.findMethod("run", "(I)I")
        assertFalse(runMethod.hasInvoke("add", "(I)I"))
        assertFalse(runMethod.hasInvoke("multiply", "(II)I"))
        assertFalse(runMethod.hasInvoke("finalAdd", "(I)I"))

        val callNoThisMethod = classNode.findMethod("callNoThis", "(L$CLASS_NAME;)I")
        assertFalse(callNoThisMethod.hasInvoke("noThis", "()I"))

        val tempDir = Files.createTempDirectory("grunteon-method-inliner-test")
        writeClasses(instance.workRes.inputClassCollection, tempDir)
        runTestClass(tempDir)
    }

    /**
     * Asserts the inliner output instruction-by-instruction against a hand-rolled
     * golden disassembly. Catches regressions that the runtime test would miss
     * (wrong local-slot indexes, missing null-check prologue, dropped instructions,
     * unexpected extra ops) without depending on COMPUTE_FRAMES masking the issue.
     */
    @Test
    fun matchesExpectedDisassembly() {
        val instance = readTestClasses(
            Basic::class.java,
            ObfConfig(
                globalConfig = GlobalConfig(
                    output = null
                ),
                transformers = listOf(
                    TransformerEntry(
                        config = MethodInliner.Config(maxInstructions = 8)
                    )
                )
            )
        )
        val classNode = instance.workRes.inputClassMap[CLASS_NAME]
            ?: error("Missing test class $CLASS_NAME")

        val runBefore = classNode.findMethod("run", "(I)I").disassemble()
        val callNoThisBefore = classNode.findMethod("callNoThis", "(L$CLASS_NAME;)I").disassemble()
        assertEquals(EXPECTED_RUN_BEFORE, runBefore, "run(I)I bytecode (before) mismatch")
        assertEquals(EXPECTED_CALLNOTHIS_BEFORE, callNoThisBefore, "callNoThis bytecode (before) mismatch")

        context(instance.workRes, instance) {
            instance.execute()
        }

        val runAfter = classNode.findMethod("run", "(I)I").disassemble()
        val callNoThisAfter = classNode.findMethod("callNoThis", "(L$CLASS_NAME;)I").disassemble()
        assertEquals(EXPECTED_RUN_AFTER, runAfter, "run(I)I bytecode (after) mismatch")
        assertEquals(EXPECTED_CALLNOTHIS_AFTER, callNoThisAfter, "callNoThis bytecode (after) mismatch")
    }

    private fun ClassNode.findMethod(name: String, desc: String): MethodNode {
        return methods.firstOrNull { it.name == name && it.desc == desc }
            ?: error("Missing method $name$desc")
    }

    private fun MethodNode.hasInvoke(name: String, desc: String): Boolean {
        return instructions.toArray().any {
            it is MethodInsnNode && it.owner == CLASS_NAME && it.name == name && it.desc == desc
        }
    }

    /**
     * Renders the method body via ASM's [Textifier] and strips metadata lines
     * (line numbers, frames, maxStack/maxLocals, local-variable tables, try-catch
     * blocks, bare label declarations) so the comparison focuses on actual ops.
     */
    private fun MethodNode.disassemble(): String {
        val textifier = Textifier()
        accept(TraceMethodVisitor(textifier))
        val sw = StringWriter()
        textifier.print(PrintWriter(sw))
        return sw.toString()
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty()
                        && !line.startsWith("LINENUMBER")
                        && !line.startsWith("FRAME")
                        && !line.startsWith("MAXSTACK")
                        && !line.startsWith("MAXLOCALS")
                        && !line.startsWith("LOCALVARIABLE")
                        && !line.startsWith("TRYCATCHBLOCK")
                        && !line.startsWith("// ")
                        && !line.startsWith("@")
                        && !LABEL_REGEX.matches(line)
            }
            .joinToString("\n")
    }

    private fun writeClasses(classNodes: Collection<ClassNode>, outputDir: Path) {
        for (classNode in classNodes) {
            val bytes = ClassWriter(COMPUTE_FRAMES).apply {
                classNode.accept(ClearClassNode(Opcodes.ASM9, this))
            }.toByteArray()
            val outputFile = outputDir.resolve(classNode.name + ".class")
            Files.createDirectories(outputFile.parent)
            outputFile.outputStream().use {
                it.write(bytes)
            }
        }
    }

    private fun runTestClass(classPath: Path) {
        val javaHome = Path.of(System.getProperty("java.home"))
        val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")
        val javaExe = javaHome.resolve("bin").resolve(if (isWindows) "java.exe" else "java").toString()
        val process = ProcessBuilder(javaExe, "-cp", classPath.absolutePathString(), CLASS_NAME.replace('/', '.'))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        assertEquals(0, exitCode, "Test class failed with exit code $exitCode:\n$output")
    }

    private companion object {
        const val CLASS_NAME = "net/spartanb312/grunteon/testcase/methodinline/Basic"
        val LABEL_REGEX = Regex("^L\\d+$")

        // Original `run(I)I` — three same-class invokes that the inliner should rewrite.
        val EXPECTED_RUN_BEFORE = """
            ALOAD 0
            ILOAD 1
            INVOKEVIRTUAL net/spartanb312/grunteon/testcase/methodinline/Basic.add (I)I
            ILOAD 1
            ICONST_3
            INVOKESTATIC net/spartanb312/grunteon/testcase/methodinline/Basic.multiply (II)I
            IADD
            ALOAD 0
            ILOAD 1
            INVOKEVIRTUAL net/spartanb312/grunteon/testcase/methodinline/Basic.finalAdd (I)I
            IADD
            IRETURN
        """.trimIndent().trim()

        // Inlined `run(I)I`. Local slots: caller starts at maxLocals=2 (this=0, value=1).
        // - add(I)I:      this->2, value->3   => maxLocals=4. Prologue ends in DUP/getClass/POP/ASTORE 2.
        // - multiply(II): static, left->4, right->5  => maxLocals=6.
        // - finalAdd(I):  this->6, value->7   => maxLocals=8.
        val EXPECTED_RUN_AFTER = """
            ALOAD 0
            ILOAD 1
            ISTORE 3
            DUP
            INVOKEVIRTUAL java/lang/Object.getClass ()Ljava/lang/Class;
            POP
            ASTORE 2
            ALOAD 2
            GETFIELD net/spartanb312/grunteon/testcase/methodinline/Basic.seed : I
            ILOAD 3
            IADD
            ILOAD 1
            ICONST_3
            ISTORE 5
            ISTORE 4
            ILOAD 4
            ILOAD 5
            IMUL
            IADD
            ALOAD 0
            ILOAD 1
            ISTORE 7
            DUP
            INVOKEVIRTUAL java/lang/Object.getClass ()Ljava/lang/Class;
            POP
            ASTORE 6
            ALOAD 6
            GETFIELD net/spartanb312/grunteon/testcase/methodinline/Basic.seed : I
            ILOAD 7
            IADD
            ICONST_1
            IADD
            IADD
            IRETURN
        """.trimIndent().trim()

        // Original `callNoThis(LBasic;)I` — single private (nestmate) invokevirtual.
        val EXPECTED_CALLNOTHIS_BEFORE = """
            ALOAD 0
            INVOKEVIRTUAL net/spartanb312/grunteon/testcase/methodinline/Basic.noThis ()I
            IRETURN
        """.trimIndent().trim()

        // Inlined `callNoThis`. caller maxLocals=1 (param), this->1; preserveNullCheck
        // keeps the DUP/getClass/POP so callNoThis(null) still NPEs.
        val EXPECTED_CALLNOTHIS_AFTER = """
            ALOAD 0
            DUP
            INVOKEVIRTUAL java/lang/Object.getClass ()Ljava/lang/Class;
            POP
            ASTORE 1
            BIPUSH 9
            IRETURN
        """.trimIndent().trim()
    }
}
