/*
 * Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.pluginverifier.verifiers.clazz

import com.jetbrains.pluginverifier.results.problems.MethodNotImplementedProblem
import com.jetbrains.pluginverifier.verifiers.VerificationContext
import com.jetbrains.pluginverifier.verifiers.hierarchy.ClassParentsVisitor
import com.jetbrains.pluginverifier.verifiers.resolution.ClassFile
import com.jetbrains.pluginverifier.verifiers.resolution.Method
import com.jetbrains.pluginverifier.verifiers.resolution.resolveClassChecked

class AbstractMethodVerifier : ClassVerifier {
  override fun verify(classFile: ClassFile, context: VerificationContext) {
    if (classFile.isAbstract || classFile.isInterface) return

    // Methods are stored instead of their locations: building a MethodLocation
    // extracts parameter names from the local variable table, which is too
    // expensive to do eagerly for every method of every parent class.
    // Locations are materialized only for the unimplemented abstract methods.
    val abstractMethods = hashMapOf<MethodSignature, Method>()
    val implementedMethods = hashSetOf<MethodSignature>()
    var hasUnresolvedParents = false

    val parentsVisitor = ClassParentsVisitor(true) { subclassFile, superName ->
      val parentFile = context.classResolver.resolveClassChecked(superName, subclassFile, context)
      hasUnresolvedParents = hasUnresolvedParents || parentFile == null
      parentFile
    }

    parentsVisitor.visitClass(classFile, true, onEnter = { parent ->
      parent.methods.forEach { method ->
        if (!method.isPrivate && !method.isStatic) {
          val methodSignature = MethodSignature(method.name, method.descriptor)
          if (method.isAbstract) {
            abstractMethods[methodSignature] = method
          } else {
            implementedMethods.add(methodSignature)
          }
        }
      }
      true
    })

    if (!hasUnresolvedParents) {
      (abstractMethods.keys - implementedMethods).forEach { method ->
        val abstractMethod = abstractMethods[method]!!
        context.problemRegistrar.registerProblem(MethodNotImplementedProblem(abstractMethod.location, classFile.location))
      }
    }
  }

  private data class MethodSignature(val name: String, val descriptor: String)

}