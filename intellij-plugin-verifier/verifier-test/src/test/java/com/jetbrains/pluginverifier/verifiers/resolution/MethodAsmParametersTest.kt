package com.jetbrains.pluginverifier.verifiers.resolution

import com.jetbrains.plugin.structure.classes.resolvers.FileOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodNode

class MethodAsmParametersTest {

  @Test
  fun `parameter names and annotations are zipped for a regular method`() {
    val method = methodNode(
      name = "foo",
      descriptor = "(ILjava/lang/String;)V",
      access = Opcodes.ACC_PUBLIC,
      localVariableNames = listOf("this", "count", "label"),
      localVariableDescriptors = listOf("Lsample/Owner;", "I", "Ljava/lang/String;")
    )
    method.invisibleParameterAnnotations = arrayOf(
      mutableListOf(AnnotationNode("Lsample/Anno;")),
      null
    )
    val parameters = methodAsm(classNode(), method).methodParameters

    assertEquals(listOf("count", "label"), parameters.map { it.name })
    assertEquals(listOf("Lsample/Anno;"), parameters[0].annotations.map { it.desc })
    assertTrue(parameters[1].annotations.isEmpty())
  }

  @Test
  fun `parameter names fall back to argN without a local variable table`() {
    val method = methodNode(
      name = "bar",
      descriptor = "(JLjava/lang/Object;)I",
      access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
      localVariableNames = emptyList(),
      localVariableDescriptors = emptyList()
    )
    val parameters = methodAsm(classNode(), method).methodParameters
    assertEquals(listOf("arg0", "arg1"), parameters.map { it.name })
  }

  @Test
  fun `enum constructor annotations are shifted past the two synthetic parameters`() {
    val enumClass = classNode(access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ENUM)
    val ctor = methodNode(
      name = "<init>",
      descriptor = "(Ljava/lang/String;IZ)V",
      access = Opcodes.ACC_PRIVATE,
      localVariableNames = listOf("this", "\$enum\$name", "\$enum\$ordinal", "flag"),
      localVariableDescriptors = listOf("Lsample/Owner;", "Ljava/lang/String;", "I", "Z")
    )
    ctor.invisibleParameterAnnotations = arrayOf(mutableListOf(AnnotationNode("Lsample/Anno;")))
    val parameters = methodAsm(enumClass, ctor).methodParameters

    assertEquals(listOf("\$enum\$name", "\$enum\$ordinal", "flag"), parameters.map { it.name })
    assertTrue(parameters[0].annotations.isEmpty())
    assertTrue(parameters[1].annotations.isEmpty())
    assertEquals(listOf("Lsample/Anno;"), parameters[2].annotations.map { it.desc })
  }

  @Test
  fun `method parameters are memoized on repeated access`() {
    val method = methodNode(
      name = "foo",
      descriptor = "(ILjava/lang/String;)V",
      access = Opcodes.ACC_PUBLIC,
      localVariableNames = listOf("this", "count", "label"),
      localVariableDescriptors = listOf("Lsample/Owner;", "I", "Ljava/lang/String;")
    )
    method.invisibleParameterAnnotations = arrayOf(null, null)
    val methodAsm = methodAsm(classNode(), method)

    val first = methodAsm.methodParameters
    val second = methodAsm.methodParameters
    assertSame("methodParameters must be computed once and memoized", first, second)
    assertEquals(listOf("count", "label"), second.map { it.name })
  }

  @Test
  fun `method location reflects parameter names`() {
    val method = methodNode(
      name = "foo",
      descriptor = "(ILjava/lang/String;)V",
      access = Opcodes.ACC_PUBLIC,
      localVariableNames = listOf("this", "count", "label"),
      localVariableDescriptors = listOf("Lsample/Owner;", "I", "Ljava/lang/String;")
    )
    val location = methodAsm(classNode(), method).location
    assertEquals(listOf("count", "label"), location.parameterNames)
  }

  private fun classNode(access: Int = Opcodes.ACC_PUBLIC): ClassNode = ClassNode().apply {
    version = Opcodes.V11
    this.access = access
    name = "sample/Owner"
    superName = "java/lang/Object"
  }

  private fun methodNode(
    name: String,
    descriptor: String,
    access: Int,
    localVariableNames: List<String>,
    localVariableDescriptors: List<String>
  ): MethodNode {
    val method = MethodNode(access, name, descriptor, null, null)
    if (localVariableNames.isNotEmpty()) {
      val start = LabelNode()
      val end = LabelNode()
      method.localVariables = localVariableNames.mapIndexed { index, variableName ->
        LocalVariableNode(variableName, localVariableDescriptors[index], null, start, end, index)
      }
    }
    return method
  }

  private fun methodAsm(classNode: ClassNode, methodNode: MethodNode): MethodAsm {
    classNode.methods.add(methodNode)
    return MethodAsm(ClassFileAsm(classNode, TestFileOrigin), methodNode)
  }

  private object TestFileOrigin : FileOrigin {
    override val parent: FileOrigin? = null
  }
}
