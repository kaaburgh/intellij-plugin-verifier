/*
 * Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.pluginverifier.repository.cleanup

import com.jetbrains.pluginverifier.repository.files.AvailableFile
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * The [sweep policy] [SweepPolicy] that selects the files based on their [last access time] [UsageStatistic.lastAccessTime].
 * If multiple files have the same last access time, the heaviest one is selected.
 *
 * The policy selects as many files as necessary until the disk usage corresponds to [diskSpaceSetting].
 */
class LruFileSizeSweepPolicy<K>(
  private val diskSpaceSetting: DiskSpaceSetting,
  private val additionalSpaceUsed: () -> SpaceAmount = { SpaceAmount.ZERO_SPACE },
  val cleanupStatistics: FileCacheCleanupStatistics = FileCacheCleanupStatistics(),
  private val clock: Clock = Clock.systemUTC()
) : SweepPolicy<K> {

  private val logger = LoggerFactory.getLogger(LruFileSizeSweepPolicy::class.java)

  private fun combinedSpaceUsed(cachedFilesSpaceUsed: SpaceAmount) = cachedFilesSpaceUsed + additionalSpaceUsed()

  private fun estimateFreeSpaceAmount(totalSpaceUsed: SpaceAmount) =
    diskSpaceSetting.maxSpaceUsage - totalSpaceUsed

  override fun isNecessary(totalSpaceUsed: SpaceAmount): Boolean =
    estimateFreeSpaceAmount(combinedSpaceUsed(totalSpaceUsed)) < diskSpaceSetting.lowSpaceThreshold

  private val lruHeaviestFilesComparator = compareBy<AvailableFile<K>> { it.usageStatistic.lastAccessTime }
    .thenByDescending { it.fileInfo.fileSize }
    .thenBy { it.fileInfo.file.fileName }

  override fun selectFilesForDeletion(sweepInfo: SweepInfo<K>): List<AvailableFile<K>> {
    if (isNecessary(sweepInfo.totalSpaceUsed)) {
      val freeFiles = sweepInfo.availableFiles.filterNot { it.isLocked }
      if (freeFiles.isNotEmpty()) {

        val sortedCandidates = freeFiles.sortedWith(lruHeaviestFilesComparator)
        val deleteFiles = arrayListOf<AvailableFile<K>>()
        val estimatedFreeSpaceAmount = estimateFreeSpaceAmount(combinedSpaceUsed(sweepInfo.totalSpaceUsed))
        var needToFreeSpace = diskSpaceSetting.minimumFreeSpaceAfterCleanup - estimatedFreeSpaceAmount
        for (candidate in sortedCandidates) {
          if (needToFreeSpace > SpaceAmount.ZERO_SPACE) {
            deleteFiles.add(candidate)
            needToFreeSpace -= candidate.fileInfo.fileSize
          } else {
            break
          }
        }

        if (deleteFiles.isNotEmpty()) {
          val freedSpace = deleteFiles.fold(SpaceAmount.ZERO_SPACE) { total, file -> total + file.fileInfo.fileSize }
          val interval = cleanupStatistics.recordCleanup(deleteFiles.size, freedSpace, clock.instant())
          logger.info(
            "Plugin disk cache cleanup #{}: deleting {} files and freeing {}; combined usage before cleanup: {}; since previous cleanup: {}",
            cleanupStatistics.cleanups,
            deleteFiles.size,
            freedSpace,
            combinedSpaceUsed(sweepInfo.totalSpaceUsed),
            interval ?: "n/a"
          )
        }
        return deleteFiles
      }
    }
    return emptyList()
  }

}
