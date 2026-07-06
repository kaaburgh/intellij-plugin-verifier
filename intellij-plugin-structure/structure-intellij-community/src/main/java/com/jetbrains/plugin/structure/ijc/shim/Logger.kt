/*
 * Copyright 2000-2026 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.ijc.shim

import org.slf4j.LoggerFactory

/**
 * Minimal SLF4J-backed replacement for `com.intellij.openapi.diagnostic.Logger`.
 *
 * Only the API surface used by the vendored `com.intellij.platform.pluginSystem.parser.impl`
 * sources is provided. Unlike the IntelliJ Platform logger, `error()` does not throw
 * in tests; it logs at the ERROR level. This is intentional: the plugin verifier must
 * never crash on a malformed third-party descriptor.
 */
class Logger(private val delegate: org.slf4j.Logger) {
  fun error(message: String) {
    delegate.error(message)
  }

  fun error(message: String, e: Throwable?) {
    delegate.error(message, e)
  }

  fun warn(message: String) {
    delegate.warn(message)
  }

  fun warn(message: String, e: Throwable?) {
    delegate.warn(message, e)
  }

  fun info(message: String) {
    delegate.info(message)
  }

  fun info(message: String, e: Throwable?) {
    delegate.info(message, e)
  }
}

inline fun <reified T : Any> logger(): Logger = getLogger(T::class.java)

fun getLogger(clazz: Class<*>): Logger = Logger(LoggerFactory.getLogger(clazz))
