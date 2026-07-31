/*
 * Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.intellij.extractor

import com.jetbrains.plugin.structure.base.utils.deleteQuietly
import org.apache.commons.io.FileUtils
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Descriptor of a plugin that has been extracted from a compressed file, usually ZIP.
 * @param pluginFile a path to the top-level directory of the plugin in the filesystem, after decompression
 * @param fileToDelete a path to the directory that contains decompressed contents of the plugin file.
 */
data class ExtractedPlugin(
  val pluginFile: Path,
  private val fileToDelete: Path
) : Closeable {
  val sizeInBytes: Long by lazy { FileUtils.sizeOf(fileToDelete.toFile()) }

  private val closed = AtomicBoolean()
  private var closeListener: ((Long) -> Unit)? = null

  fun onClose(listener: (Long) -> Unit) {
    closeListener = listener
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      val size = sizeInBytes
      fileToDelete.deleteQuietly()
      closeListener?.invoke(size)
    }
  }
}
