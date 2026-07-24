/*
 * Copyright 2000-2025 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.intellij.plugin.enums

private const val OS_MODULE_PREFIX = "com.intellij.modules.os."

enum class OS(private val suffix: String) {
  Windows("windows"),
  Unix("unix"),
  MacOS("mac"),
  Linux("linux"),
  FreeBSD("freebsd");

  val pluginAlias = OS_MODULE_PREFIX + suffix

  companion object {
    const val MODULE_PREFIX = OS_MODULE_PREFIX

    fun getByModule(moduleName: String): OS? = values().find {
      it.pluginAlias.equals(moduleName, ignoreCase = true)
    }

    /**
     * Indicates that [moduleName] declares an operating system constraint, such as `com.intellij.modules.os.windows`.
     *
     * Unlike [getByModule], this also accepts operating systems that are not modeled by this enumeration yet.
     */
    fun isOsModule(moduleName: String): Boolean =
      moduleName.length > MODULE_PREFIX.length && moduleName.startsWith(MODULE_PREFIX, ignoreCase = true)
  }
}