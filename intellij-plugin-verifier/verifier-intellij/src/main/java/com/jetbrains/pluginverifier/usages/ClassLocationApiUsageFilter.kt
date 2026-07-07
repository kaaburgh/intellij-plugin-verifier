package com.jetbrains.pluginverifier.usages

import com.jetbrains.pluginverifier.results.location.ClassLocation
import com.jetbrains.pluginverifier.results.reference.ClassReference
import com.jetbrains.pluginverifier.results.reference.FieldReference
import com.jetbrains.pluginverifier.verifiers.VerificationContext
import com.jetbrains.pluginverifier.verifiers.filter.ApiUsageFilter
import com.jetbrains.pluginverifier.verifiers.resolution.ClassFile
import com.jetbrains.pluginverifier.verifiers.resolution.ClassFileMember
import com.jetbrains.pluginverifier.verifiers.resolution.ClassUsageType
import com.jetbrains.pluginverifier.verifiers.resolution.Field
import com.jetbrains.pluginverifier.verifiers.resolution.Method
import org.objectweb.asm.tree.AbstractInsnNode

open class ClassLocationApiUsageFilter : ApiUsageFilter {
  // The containing class location is obtained as 'containingClassFile.location'
  // rather than 'location.containingClass': the resulting ClassLocation is
  // identical by construction, but this avoids materializing the full member
  // location - including method parameter name extraction - on this
  // per-instruction hot path.
  override fun allow(
    classReference: ClassReference,
    invocationTarget: ClassFile,
    caller: ClassFileMember,
    usageType: ClassUsageType,
    context: VerificationContext
  ): Boolean {
    val usageHost = caller.containingClassFile.location
    val apiHost = invocationTarget.location
    return allow(usageHost, apiHost)
  }

  override fun allow(
    invokedMethod: Method,
    invocationInstruction: AbstractInsnNode,
    callerMethod: Method,
    context: VerificationContext
  ): Boolean {
    val usageHost = callerMethod.containingClassFile.location
    val apiHost = invokedMethod.containingClassFile.location
    return allow(usageHost, apiHost)
  }

  override fun allow(
    fieldReference: FieldReference,
    resolvedField: Field,
    callerMethod: Method,
    context: VerificationContext
  ): Boolean {
    val apiHost = callerMethod.containingClassFile.location
    val usageHost = resolvedField.containingClassFile.location
    return allow(usageHost, apiHost)
  }

  open fun allow(usageLocation: ClassLocation, apiLocation: ClassLocation): Boolean = true
}