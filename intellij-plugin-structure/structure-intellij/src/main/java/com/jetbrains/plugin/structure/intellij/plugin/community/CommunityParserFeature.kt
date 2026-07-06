/*
 * Copyright 2000-2026 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.intellij.plugin.community

/**
 * Feature gate for the experimental plugin descriptor parsing path backed by the
 * intellij-community implementation (`com.intellij.platform.pluginSystem.parser.impl`).
 *
 * Disabled by default: the legacy JDOM/JAXB pipeline remains the production path.
 * Enable either with the [SYSTEM_PROPERTY] system property or programmatically via [forcedEnabled]
 * (used by the parity test suite).
 *
 * This object is experimental and is not part of the stable API of this library.
 * See `structure-intellij-community/DESIGN.md`.
 */
object CommunityParserFeature {
  const val SYSTEM_PROPERTY = "intellij.plugin.structure.community.parser"

  /**
   * Programmatic override: `true`/`false` take precedence over the system property,
   * `null` (default) defers to the system property.
   */
  @Volatile
  var forcedEnabled: Boolean? = null

  val isEnabled: Boolean
    get() = forcedEnabled ?: System.getProperty(SYSTEM_PROPERTY).toBoolean()
}
