/*
 * Copyright 2000-2026 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.pluginverifier.repository.cleanup

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Aggregate statistics for disk-cache cleanups that actually selected files for deletion. */
class FileCacheCleanupStatistics {
  private val cleanupCount = AtomicLong()
  private val deletedFileCount = AtomicLong()
  private val freedBytes = AtomicLong()

  @Volatile
  private var firstCleanup: Instant? = null

  @Volatile
  private var lastCleanup: Instant? = null

  @Synchronized
  fun recordCleanup(files: Int, freedSpace: SpaceAmount, timestamp: Instant): Duration? {
    val previousCleanup = lastCleanup
    if (firstCleanup == null) firstCleanup = timestamp
    lastCleanup = timestamp
    cleanupCount.incrementAndGet()
    deletedFileCount.addAndGet(files.toLong())
    freedBytes.addAndGet(freedSpace.to(SpaceUnit.BYTE).toLong())
    return previousCleanup?.let { Duration.between(it, timestamp) }
  }

  val cleanups: Long get() = cleanupCount.get()
  val deletedFiles: Long get() = deletedFileCount.get()
  val freedSpace: SpaceAmount get() = SpaceAmount.ofBytes(freedBytes.get())

  val averageInterval: Duration?
    get() {
      val count = cleanups
      val first = firstCleanup
      val last = lastCleanup
      return if (count < 2 || first == null || last == null) null else Duration.between(first, last).dividedBy(count - 1)
    }

  val presentableSummary: String
    get() = "cleanups=$cleanups, deleted-files=$deletedFiles, freed=${freedSpace.presentableAmount()}, " +
      "average-interval=${averageInterval?.toString() ?: "n/a"}"
}
