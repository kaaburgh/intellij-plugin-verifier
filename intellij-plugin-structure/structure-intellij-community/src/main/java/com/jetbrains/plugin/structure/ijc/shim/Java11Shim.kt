/*
 * Copyright 2000-2026 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.ijc.shim

/**
 * Minimal replacement for `com.intellij.util.containers.Java11Shim`.
 *
 * The IntelliJ Platform uses this indirection to pick optimal immutable collection
 * factories. Here it simply delegates to the JDK 11+ `copyOf`/`of` factories,
 * which is exactly what the platform's default implementation does.
 */
class Java11Shim private constructor() {
  fun <T> copyOf(collection: Collection<T>): List<T> = java.util.List.copyOf(collection)

  fun <T> listOf(): List<T> = java.util.List.of()

  fun <K, V> mapOf(): Map<K, V> = java.util.Map.of()

  companion object {
    @JvmField
    val INSTANCE: Java11Shim = Java11Shim()
  }
}
