package com.jetbrains.plugin.structure.intellij.community

import com.jetbrains.plugin.structure.base.plugin.PluginCreationResult
import com.jetbrains.plugin.structure.base.utils.contentBuilder.buildZipFile
import com.jetbrains.plugin.structure.intellij.plugin.IdePlugin
import com.jetbrains.plugin.structure.intellij.plugin.community.CommunityParserFeature
import com.jetbrains.plugin.structure.mocks.IdePluginManagerTest
import com.jetbrains.plugin.structure.rules.FileSystemType
import org.junit.After
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Parity tests over descriptors of real, open-source JetBrains Marketplace plugins
 * (see `src/test/resources/community-parity/README.md` for provenance).
 * The descriptor directories are assembled into plugin JARs at runtime.
 */
class RealPluginDescriptorParityTest(fileSystemType: FileSystemType) : IdePluginManagerTest(fileSystemType) {

  @After
  fun resetFeature() {
    CommunityParserFeature.forcedEnabled = null
  }

  @Test
  fun `sonarlint descriptor parses identically`() {
    assertFixtureParity("sonarlint")
  }

  @Test
  fun `asciidoctor descriptor parses identically`() {
    assertFixtureParity("asciidoc")
  }

  @Test
  fun `detekt descriptor parses identically`() {
    assertFixtureParity("detekt")
  }

  private fun assertFixtureParity(fixtureName: String) {
    val jar = buildFixtureJar(fixtureName)
    val legacy = parse(jar, communityParser = false)
    val community = parse(jar, communityParser = true)
    ParityAssertions.assertParity(legacy, community)
  }

  private fun parse(pluginFile: Path, communityParser: Boolean): PluginCreationResult<IdePlugin> {
    CommunityParserFeature.forcedEnabled = communityParser
    try {
      return createManager(temporaryFolder.newFolder()).createPlugin(pluginFile)
    } finally {
      CommunityParserFeature.forcedEnabled = null
    }
  }

  private fun buildFixtureJar(fixtureName: String): Path {
    val fixtureRoot = fixtureDirectory(fixtureName)
    return buildZipFile(temporaryFolder.newFile("$fixtureName.jar")) {
      dir("META-INF", fixtureRoot.resolve("META-INF"))
    }
  }

  private fun fixtureDirectory(fixtureName: String): Path {
    val url = javaClass.classLoader.getResource("community-parity/$fixtureName/META-INF/plugin.xml")
      ?: throw AssertionError("Fixture $fixtureName is missing from test resources")
    return Paths.get(url.toURI()).parent.parent
  }
}
