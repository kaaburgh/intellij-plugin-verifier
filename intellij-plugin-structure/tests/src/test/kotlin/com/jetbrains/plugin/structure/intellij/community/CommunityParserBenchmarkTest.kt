package com.jetbrains.plugin.structure.intellij.community

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import com.jetbrains.plugin.structure.base.plugin.PluginCreationSuccess
import com.jetbrains.plugin.structure.base.utils.contentBuilder.buildZipFile
import com.jetbrains.plugin.structure.base.utils.createDir
import com.jetbrains.plugin.structure.intellij.plugin.PluginArchiveManager
import com.jetbrains.plugin.structure.intellij.plugin.community.CommunityParserFeature
import com.jetbrains.plugin.structure.intellij.plugin.createIdePluginManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path


/**
 * A coarse throughput/allocation comparison of the legacy and the community-backed descriptor
 * parsing paths. This is not a rigorous JMH benchmark — it exists to catch order-of-magnitude
 * regressions and to produce the numbers quoted in `structure-intellij-community/DESIGN.md`.
 *
 * The workload is the real SonarLint for IntelliJ descriptor (~48 KB plugin.xml + 10 optional
 * dependency descriptors) assembled into a JAR. Runs on both the default and the in-memory
 * (Jimfs) file system.
 */
class CommunityParserBenchmarkTest {

  private val warmupIterations = 30
  private val iterations = 100

  @After
  fun resetFeature() {
    CommunityParserFeature.forcedEnabled = null
  }

  @Test
  fun `compare legacy and community parser on the default file system`() {
    val workDir = Files.createTempDirectory("parser-benchmark")
    try {
      runComparison("default-fs", workDir)
    } finally {
      workDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `compare legacy and community parser on Jimfs`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs: FileSystem ->
      runComparison("jimfs", fs.getPath("/benchmark").createDir())
    }
  }

  private fun runComparison(label: String, workDir: Path) {
    val jar = buildSonarLintJar(workDir)
    val legacy = measure(jar, workDir.resolve("extract-legacy").createDir(), communityParser = false)
    val community = measure(jar, workDir.resolve("extract-community").createDir(), communityParser = true)
    println("[$label] legacy:    $legacy")
    println("[$label] community: $community")
    // A generous sanity bound: the community path serializes JDOM and re-parses, so some
    // overhead is expected, but it must stay within one order of magnitude.
    assertTrue(
      "community path is ${community.nanosPerOp / legacy.nanosPerOp.coerceAtLeast(1)}x slower than legacy",
      community.nanosPerOp < legacy.nanosPerOp * 10
    )
  }

  private fun measure(jar: Path, extractDir: Path, communityParser: Boolean): Measurement {
    CommunityParserFeature.forcedEnabled = communityParser
    try {
      PluginArchiveManager(extractDir).use { archiveManager ->
        val manager = createIdePluginManager(archiveManager)
        repeat(warmupIterations) {
          val result = manager.createPlugin(jar)
          check(result is PluginCreationSuccess) {
            "expected successful plugin creation, got: $result"
          }
        }
        val threadMxBean = ManagementFactory.getThreadMXBean()
        val allocatedBefore = allocatedBytes(threadMxBean)
        val start = System.nanoTime()
        repeat(iterations) {
          check(manager.createPlugin(jar) is PluginCreationSuccess)
        }
        val elapsed = System.nanoTime() - start
        val allocated = allocatedBytes(threadMxBean)?.let { after -> allocatedBefore?.let { after - it } }
        return Measurement(elapsed / iterations, allocated?.div(iterations))
      }
    } finally {
      CommunityParserFeature.forcedEnabled = null
    }
  }

  private fun allocatedBytes(threadMxBean: java.lang.management.ThreadMXBean): Long? {
    return (threadMxBean as? com.sun.management.ThreadMXBean)
      ?.takeIf { it.isThreadAllocatedMemorySupported }
      ?.getThreadAllocatedBytes(Thread.currentThread().id)
  }

  private data class Measurement(val nanosPerOp: Long, val bytesPerOp: Long?) {
    override fun toString(): String {
      val millis = "%.2f ms/op".format(nanosPerOp / 1_000_000.0)
      val memory = bytesPerOp?.let { ", %.2f MB allocated/op".format(it / (1024.0 * 1024.0)) } ?: ""
      return millis + memory
    }
  }

  private fun buildSonarLintJar(workDir: Path): Path {
    val fixtureUrl = javaClass.classLoader.getResource("community-parity/sonarlint/META-INF/plugin.xml")
      ?: throw AssertionError("sonarlint fixture is missing")
    val fixtureMetaInf = java.nio.file.Paths.get(fixtureUrl.toURI()).parent
    return buildZipFile(workDir.resolve("sonarlint.jar")) {
      dir("META-INF") {
        Files.list(fixtureMetaInf).use { descriptors ->
          descriptors.sorted().forEach { descriptorFile ->
            val fileName = descriptorFile.fileName.toString()
            if (fileName == "plugin.xml") {
              // the real descriptor gets <idea-version> injected by the plugin build;
              // inject it here as well so that creation succeeds
              val patched = String(Files.readAllBytes(descriptorFile))
                .replace("</idea-plugin>", """<idea-version since-build="223.1"/></idea-plugin>""")
              file(fileName, patched)
            } else {
              file(fileName, Files.readAllBytes(descriptorFile))
            }
          }
        }
      }
    }
  }
}
