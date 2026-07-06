package com.jetbrains.plugin.structure.intellij.community

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import com.jetbrains.plugin.structure.base.plugin.PluginCreationResult
import com.jetbrains.plugin.structure.base.utils.contentBuilder.buildDirectory
import com.jetbrains.plugin.structure.base.utils.contentBuilder.buildZipFile
import com.jetbrains.plugin.structure.base.utils.createDir
import com.jetbrains.plugin.structure.intellij.plugin.IdePlugin
import com.jetbrains.plugin.structure.intellij.plugin.PluginArchiveManager
import com.jetbrains.plugin.structure.intellij.plugin.community.CommunityParserFeature
import com.jetbrains.plugin.structure.intellij.plugin.createIdePluginManager
import org.junit.After
import org.junit.Test
import java.nio.file.Path

/**
 * Windows-path edge cases: the plugin artifact and the extraction directory live on an in-memory
 * file system with Windows semantics (`C:\` roots, backslash separators, case-insensitive names).
 */
class WindowsPathParityTest {

  @After
  fun resetFeature() {
    CommunityParserFeature.forcedEnabled = null
  }

  @Test
  fun `plugin jar with xi include on a windows-like file system parses identically`() {
    Jimfs.newFileSystem(Configuration.windows()).use { fs ->
      val work = fs.getPath("C:\\work").createDir()
      val jar = buildZipFile(work.resolve("my plugin.jar")) {
        dir("META-INF") {
          file("plugin.xml") {
            CommunityParserParityTest.descriptor("""<xi:include href="included.xml"/>""")
          }
          file("included.xml") {
            """
            <idea-plugin>
              <extensions defaultExtensionNs="com.intellij">
                <applicationService serviceImplementation="com.example.WindowsService"/>
              </extensions>
            </idea-plugin>
            """.trimIndent()
          }
        }
      }
      assertParity(jar, work)
    }
  }

  @Test
  fun `plugin directory with nested spaces on a windows-like file system parses identically`() {
    Jimfs.newFileSystem(Configuration.windows()).use { fs ->
      val work = fs.getPath("C:\\Program Files\\My Plugins").createDir()
      val pluginDir = buildDirectory(work.resolve("plugin dir").createDir()) {
        dir("META-INF") {
          file("plugin.xml") { CommunityParserParityTest.PLAIN_PLUGIN_XML }
        }
      }
      assertParity(pluginDir, work)
    }
  }

  private fun assertParity(pluginFile: Path, work: Path) {
    val legacy = parse(pluginFile, work.resolve("extract-legacy").createDir(), communityParser = false)
    val community = parse(pluginFile, work.resolve("extract-community").createDir(), communityParser = true)
    ParityAssertions.assertParity(legacy, community)
  }

  private fun parse(pluginFile: Path, extractDir: Path, communityParser: Boolean): PluginCreationResult<IdePlugin> {
    CommunityParserFeature.forcedEnabled = communityParser
    try {
      PluginArchiveManager(extractDir).use { archiveManager ->
        return createIdePluginManager(archiveManager).createPlugin(pluginFile)
      }
    } finally {
      CommunityParserFeature.forcedEnabled = null
    }
  }
}
