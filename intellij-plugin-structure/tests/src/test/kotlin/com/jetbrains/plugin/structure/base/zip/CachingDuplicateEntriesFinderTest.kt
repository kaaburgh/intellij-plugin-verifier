/*
 * Copyright 2000-2026 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.base.zip

import com.jetbrains.plugin.structure.base.utils.createZipWithDuplicateEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class CachingDuplicateEntriesFinderTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  private val finder = CachingDuplicateEntriesFinder()

  @Test
  fun `duplicate entries are reported`() {
    val zipPath = temporaryFolder.newFile("duplicates.zip").toPath()
    createZipWithDuplicateEntry(zipPath, "META-INF/plugin.xml", "<idea-plugin />", "<idea-plugin />")

    assertEquals(setOf("META-INF/plugin.xml"), finder.findDuplicateEntries(zipPath))
  }

  @Test
  fun `archive without duplicate entries is reported as clean`() {
    val zipPath = temporaryFolder.newFile("clean.zip").toPath()
    createZip(zipPath, ZipEntrySpec.Plain("META-INF/plugin.xml", "<idea-plugin />"))

    assertEquals(emptySet<String>(), finder.findDuplicateEntries(zipPath))
  }

  @Test
  fun `scan result is memoized while the archive is unchanged`() {
    val zipPath = temporaryFolder.newFile("cached.zip").toPath()
    createZip(zipPath, ZipEntrySpec.Plain("META-INF/plugin.xml", "<idea-plugin />"))
    assertEquals(emptySet<String>(), finder.findDuplicateEntries(zipPath))

    // corrupt the archive in place, keeping the same size and last-modified time:
    // a rescan would fail with ZipArchiveException, a cache hit returns the previous result
    val lastModified = Files.getLastModifiedTime(zipPath)
    zeroOut(zipPath)
    Files.setLastModifiedTime(zipPath, lastModified)

    assertEquals(emptySet<String>(), finder.findDuplicateEntries(zipPath))
  }

  @Test
  fun `rewritten archive is scanned again`() {
    val zipPath = temporaryFolder.newFile("rewritten.zip").toPath()
    createZip(zipPath, ZipEntrySpec.Plain("META-INF/plugin.xml", "<idea-plugin />"))
    assertEquals(emptySet<String>(), finder.findDuplicateEntries(zipPath))

    createZipWithDuplicateEntry(zipPath, "META-INF/plugin.xml", "<idea-plugin />", "<idea-plugin />")
    val lastModified = Files.getLastModifiedTime(zipPath)
    Files.setLastModifiedTime(zipPath, FileTime.fromMillis(lastModified.toMillis() + 2_000))

    assertEquals(setOf("META-INF/plugin.xml"), finder.findDuplicateEntries(zipPath))
  }

  private fun zeroOut(path: Path) {
    Files.write(path, ByteArray(Files.size(path).toInt()))
  }
}
