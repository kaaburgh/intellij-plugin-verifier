/*
 * Copyright 2000-2026 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.intellij.plugin.community

import com.intellij.platform.pluginSystem.parser.impl.LoadedXIncludeReference
import com.intellij.platform.pluginSystem.parser.impl.XIncludeLoader
import com.jetbrains.plugin.structure.base.utils.simpleName
import com.jetbrains.plugin.structure.intellij.resources.ResourceResolver
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Path

private const val META_INF_DIR = "META-INF"

/**
 * Implements the intellij-community [XIncludeLoader] contract on top of the verifier's
 * [ResourceResolver].
 *
 * The community parser always requests a *root-relative load path* (as produced by
 * `LoadPathUtil.toLoadPath`), e.g. `META-INF/extensions.xml` or `intellij.some.module.xml`,
 * mirroring how the IDE resolves includes through the plugin classloader from a resource root.
 * This loader resolves such paths against the resource root of the descriptor being parsed:
 * the parent of the `META-INF` directory for `META-INF/plugin.xml`-style descriptors, or the
 * descriptor's own directory for root-level (v2 module) descriptors.
 *
 * Note that this is intentionally *stricter* than the legacy [com.jetbrains.plugin.structure.intellij.xinclude.XIncluder],
 * which additionally probes parent directories and `META-INF` of every base — locations that the
 * IDE runtime would not consult. See `structure-intellij-community/DESIGN.md`, section 5.
 */
internal class ResourceResolverXIncludeLoader(
  private val resourceResolver: ResourceResolver,
  descriptorPath: Path
) : XIncludeLoader {

  private val resourceRootAnchor: Path? = computeResourceRoot(descriptorPath)?.resolve(ANCHOR_NAME)

  override fun loadXIncludeReference(path: String): LoadedXIncludeReference? {
    val anchor = resourceRootAnchor ?: return null
    return when (val result = resourceResolver.resolveResource(path, anchor)) {
      is ResourceResolver.Result.Found -> result.use {
        LoadedXIncludeReference(it.resourceStream.readBytes(), it.description)
      }
      is ResourceResolver.Result.NotFound -> null
      is ResourceResolver.Result.Failed -> throw IOException(
        "Failed to load XInclude target '$path': ${result.exception.message}", result.exception
      )
    }
  }

  private fun computeResourceRoot(descriptorPath: Path): Path? {
    val parent = descriptorPath.parent ?: return fileSystemRoot(descriptorPath)
    return if (parent.simpleName == META_INF_DIR) {
      parent.parent ?: fileSystemRoot(descriptorPath)
    } else {
      parent
    }
  }

  private fun fileSystemRoot(path: Path): Path? {
    return if (path.fileSystem != FileSystems.getDefault()) {
      path.fileSystem.rootDirectories.firstOrNull()
    } else {
      null
    }
  }

  override fun toString(): String = "ResourceResolverXIncludeLoader(root=${resourceRootAnchor?.parent})"

  private companion object {
    /**
     * [ResourceResolver.resolveResource] resolves a relative path against the *sibling* of its
     * base path; an anchor file name is appended to the resource root so that sibling resolution
     * yields `<resourceRoot>/<relativePath>`.
     */
    const val ANCHOR_NAME = "__xinclude_anchor__"
  }
}
