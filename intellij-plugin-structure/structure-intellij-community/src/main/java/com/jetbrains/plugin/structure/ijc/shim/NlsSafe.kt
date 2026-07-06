/*
 * Copyright 2000-2026 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.ijc.shim

/**
 * No-op replacement for `com.intellij.openapi.util.NlsSafe`.
 * The original is a documentation-only annotation used by IDE inspections.
 */
@Target(
  AnnotationTarget.TYPE,
  AnnotationTarget.TYPE_PARAMETER,
  AnnotationTarget.VALUE_PARAMETER,
  AnnotationTarget.FIELD,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.LOCAL_VARIABLE
)
@Retention(AnnotationRetention.BINARY)
annotation class NlsSafe
