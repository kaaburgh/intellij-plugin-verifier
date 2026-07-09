package com.jetbrains.plugin.structure.mocks

import com.jetbrains.plugin.structure.base.utils.contentBuilder.buildZipFile
import com.jetbrains.plugin.structure.intellij.problems.DuplicatedDependencyWarning
import com.jetbrains.plugin.structure.rules.FileSystemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatedOptionalDependencyConfigTest(fileSystemType: FileSystemType) : IdePluginManagerTest(fileSystemType) {

  @Test
  fun `two optional depends on the same plugin with different config files are both resolved`() {
    val pluginFile = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          perfectXmlBuilder.modify {
            depends = """
              <depends>com.intellij.modules.lang</depends>
              <depends optional="true" config-file="optA.xml">com.foo.dep</depends>
              <depends optional="true" config-file="optB.xml">com.foo.dep</depends>
            """.trimIndent()
          }
        }
        file("optA.xml", "<idea-plugin></idea-plugin>")
        file("optB.xml", "<idea-plugin></idea-plugin>")
      }
    }

    val result = createPluginSuccessfully(pluginFile)

    assertEquals(
      listOf("optA.xml", "optB.xml"),
      result.plugin.optionalDescriptors.map { it.configurationFilePath }
    )
    assertTrue(
      "Expected DuplicatedDependencyWarning among: ${result.warnings}",
      result.warnings.any { it is DuplicatedDependencyWarning }
    )
  }

  @Test
  fun `exactly duplicated optional depends is resolved only once`() {
    val pluginFile = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          perfectXmlBuilder.modify {
            depends = """
              <depends>com.intellij.modules.lang</depends>
              <depends optional="true" config-file="optA.xml">com.foo.dep</depends>
              <depends optional="true" config-file="optA.xml">com.foo.dep</depends>
            """.trimIndent()
          }
        }
        file("optA.xml", "<idea-plugin></idea-plugin>")
      }
    }

    val result = createPluginSuccessfully(pluginFile)

    assertEquals(
      listOf("optA.xml"),
      result.plugin.optionalDescriptors.map { it.configurationFilePath }
    )
  }
}
