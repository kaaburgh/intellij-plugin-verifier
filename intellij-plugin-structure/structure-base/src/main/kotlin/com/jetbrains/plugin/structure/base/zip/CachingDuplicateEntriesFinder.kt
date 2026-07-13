/*
 * Copyright 2000-2026 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.base.zip

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

private const val MAX_CACHED_ARCHIVES: Long = 1_024

/**
 * Finds [duplicate file entries][ZipHandler.findDuplicateEntries] in ZIP archives,
 * memoizing results so that each archive is fully scanned at most once.
 *
 * Plugin creation may visit the same JAR many times — once per content module or optional
 * dependency descriptor lookup — and the duplicate-entry scan iterates all archive entries,
 * which is costly for large archives.
 *
 * Cached results are keyed by the archive path, file size and last modification time,
 * so an archive that is rewritten in place is scanned again.
 */
class CachingDuplicateEntriesFinder(maximumCacheSize: Long = MAX_CACHED_ARCHIVES) {

  private val cache: Cache<Key, Set<String>> = Caffeine.newBuilder()
    .maximumSize(maximumCacheSize)
    .build()

  @Throws(ZipArchiveException::class)
  fun findDuplicateEntries(zipPath: Path): Set<String> {
    val key = getKey(zipPath) ?: return zipPath.newZipHandler().findDuplicateEntries()
    cache.getIfPresent(key)?.let { return it }
    return zipPath.newZipHandler().findDuplicateEntries().also {
      cache.put(key, it)
    }
  }

  private fun getKey(zipPath: Path): Key? = try {
    val attributes = Files.readAttributes(zipPath, BasicFileAttributes::class.java)
    Key(zipPath.toAbsolutePath(), attributes.size(), attributes.lastModifiedTime().toMillis())
  } catch (e: IOException) {
    null
  }

  private data class Key(val path: Path, val size: Long, val lastModifiedMillis: Long)
}
