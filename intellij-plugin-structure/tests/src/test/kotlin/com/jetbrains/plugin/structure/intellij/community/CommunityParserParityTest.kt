package com.jetbrains.plugin.structure.intellij.community

import com.jetbrains.plugin.structure.base.plugin.PluginCreationFail
import com.jetbrains.plugin.structure.base.plugin.PluginCreationResult
import com.jetbrains.plugin.structure.base.plugin.PluginCreationSuccess
import com.jetbrains.plugin.structure.base.utils.contentBuilder.ContentBuilder
import com.jetbrains.plugin.structure.base.utils.contentBuilder.buildDirectory
import com.jetbrains.plugin.structure.base.utils.contentBuilder.buildZipFile
import com.jetbrains.plugin.structure.intellij.plugin.IdePlugin
import com.jetbrains.plugin.structure.intellij.plugin.community.CommunityParserFeature
import com.jetbrains.plugin.structure.mocks.IdePluginManagerTest
import com.jetbrains.plugin.structure.rules.FileSystemType
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Differential tests: parse the same plugin artifact with the legacy JDOM/JAXB pipeline and with
 * the community-parser-backed pipeline ([CommunityParserFeature]) and assert deep semantic
 * equality of the results. Runs on both the default and the in-memory (Jimfs) file systems.
 */
class CommunityParserParityTest(fileSystemType: FileSystemType) : IdePluginManagerTest(fileSystemType) {

  @After
  fun resetFeature() {
    CommunityParserFeature.forcedEnabled = null
  }

  private fun parseBothWays(pluginFile: Path): Pair<PluginCreationResult<IdePlugin>, PluginCreationResult<IdePlugin>> {
    CommunityParserFeature.forcedEnabled = false
    val legacy = createManager(extractedDirectory).createPlugin(pluginFile)
    CommunityParserFeature.forcedEnabled = true
    val community = try {
      createManager(temporaryFolder.newFolder("extract-community")).createPlugin(pluginFile)
    } finally {
      CommunityParserFeature.forcedEnabled = null
    }
    return legacy to community
  }

  private fun assertParityOnSuccess(pluginFile: Path) {
    val (legacy, community) = parseBothWays(pluginFile)
    assertTrue(
      "legacy path is expected to succeed, but failed with: " +
        ((legacy as? PluginCreationFail)?.errorsAndWarnings?.joinToString() ?: ""),
      legacy is PluginCreationSuccess
    )
    ParityAssertions.assertParity(legacy, community)
  }

  private fun assertParityOnFailure(pluginFile: Path) {
    val (legacy, community) = parseBothWays(pluginFile)
    assertTrue("legacy path is expected to fail, but succeeded", legacy is PluginCreationFail)
    ParityAssertions.assertParity(legacy, community)
  }

  @Test
  fun `plain plugin xml in a JAR`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") { PLAIN_PLUGIN_XML }
      }
    }
    assertParityOnSuccess(jar)
  }

  @Test
  fun `plain plugin xml in a ZIP with lib layout`() {
    val zip = buildZipFile(temporaryFolder.newFile("plugin.zip")) {
      dir("plugin") {
        dir("lib") {
          zip("plugin.jar") {
            dir("META-INF") {
              file("plugin.xml") { PLAIN_PLUGIN_XML }
            }
          }
        }
      }
    }
    assertParityOnSuccess(zip)
  }

  @Test
  fun `plain plugin xml in a directory`() {
    val dir = buildDirectory(temporaryFolder.newFolder("plugin")) {
      dir("META-INF") {
        file("plugin.xml") { PLAIN_PLUGIN_XML }
      }
    }
    assertParityOnSuccess(dir)
  }

  @Test
  fun `plugin with optional dependencies`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor(
            """
            <depends>com.intellij.modules.lang</depends>
            <depends optional="true" config-file="optional-git.xml">Git4Idea</depends>
            <depends optional="true" config-file="optional-yaml.xml">org.jetbrains.plugins.yaml</depends>
            """
          )
        }
        file("optional-git.xml") {
          """
          <idea-plugin>
            <depends>from.optional.depends</depends>
            <extensions defaultExtensionNs="com.intellij">
              <toolWindow id="Git Tool" anchor="left" factoryClass="com.example.GitToolWindowFactory"/>
            </extensions>
          </idea-plugin>
          """.trimIndent()
        }
        file("optional-yaml.xml") {
          """
          <idea-plugin>
            <extensions defaultExtensionNs="com.intellij">
              <applicationService serviceImplementation="com.example.YamlService"/>
            </extensions>
          </idea-plugin>
          """.trimIndent()
        }
      }
    }
    assertParityOnSuccess(jar)
  }

  @Test
  fun `plugin with unresolved optional dependency descriptor`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor("""<depends optional="true" config-file="missing.xml">Git4Idea</depends>""")
        }
      }
    }
    // legacy path succeeds with a warning about the missing config file
    assertParityOnSuccess(jar)
  }

  @Test
  fun `plugin with xi include of a sibling in META-INF`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor("""<xi:include href="included.xml" xpointer="xpointer(/idea-plugin/*)"/>""")
        }
        file("included.xml") {
          """
          <idea-plugin>
            <extensions defaultExtensionNs="com.intellij">
              <projectService serviceImplementation="com.example.IncludedService"/>
              <toolWindow id="Included" factoryClass="com.example.Included"/>
            </extensions>
            <actions>
              <action id="included.action" class="com.example.IncludedAction" text="Included"/>
            </actions>
          </idea-plugin>
          """.trimIndent()
        }
      }
    }
    assertParityOnSuccess(jar)
  }

  @Test
  fun `plugin with xi include with absolute href at resource root`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor("""<xi:include href="/root-config.xml"/>""")
        }
      }
      file("root-config.xml") {
        """
        <idea-plugin>
          <extensions defaultExtensionNs="com.intellij">
            <applicationService serviceImplementation="com.example.RootService"/>
          </extensions>
        </idea-plugin>
        """.trimIndent()
      }
    }
    assertParityOnSuccess(jar)
  }

  @Test
  fun `plugin with missing optional xi include with fallback`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor(
            """
            <xi:include href="not-there.xml">
              <xi:fallback/>
            </xi:include>
            """
          )
        }
      }
    }
    assertParityOnSuccess(jar)
  }

  @Test
  fun `plugin with missing mandatory xi include fails in both modes`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor("""<xi:include href="not-there.xml"/>""")
        }
      }
    }
    assertParityOnFailure(jar)
  }

  @Test
  fun `plugin with transitive xi includes`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor("""<xi:include href="level1.xml"/>""")
        }
        file("level1.xml") {
          """
          <idea-plugin>
            <xi:include xmlns:xi="http://www.w3.org/2001/XInclude" href="level2.xml"/>
            <extensions defaultExtensionNs="com.intellij">
              <applicationService serviceImplementation="com.example.Level1Service"/>
            </extensions>
          </idea-plugin>
          """.trimIndent()
        }
        file("level2.xml") {
          """
          <idea-plugin>
            <extensions defaultExtensionNs="com.intellij">
              <applicationService serviceImplementation="com.example.Level2Service"/>
            </extensions>
          </idea-plugin>
          """.trimIndent()
        }
      }
    }
    assertParityOnSuccess(jar)
  }

  @Test
  fun `plugin with v2 content modules`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor(
            """
            <depends>com.intellij.modules.platform</depends>
            <dependencies>
              <plugin id="org.jetbrains.plugins.terminal"/>
              <module name="intellij.platform.collaborationTools"/>
            </dependencies>
            <content>
              <module name="someId.optional.module"/>
              <module name="someId.required.module" loading="required"/>
              <module name="someId.embedded.module" loading="embedded"/>
            </content>
            """,
            attributes = """package="com.example""""
          )
        }
      }
      file("someId.optional.module.xml") {
        """
        <idea-plugin package="com.example.optional">
          <dependencies>
            <module name="someId.required.module"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <applicationService serviceImplementation="com.example.OptionalModuleService"/>
          </extensions>
        </idea-plugin>
        """.trimIndent()
      }
      file("someId.required.module.xml") {
        """
        <idea-plugin package="com.example.required">
          <extensions defaultExtensionNs="com.intellij">
            <projectService serviceImplementation="com.example.RequiredModuleService"/>
          </extensions>
        </idea-plugin>
        """.trimIndent()
      }
      file("someId.embedded.module.xml") {
        """
        <idea-plugin package="com.example.embedded">
        </idea-plugin>
        """.trimIndent()
      }
    }
    assertParityOnSuccess(jar)
  }

  @Test
  fun `plugin with inline content module and namespaces`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor(
            """
            <content namespace="example-namespace">
              <module name="someId.inline"><![CDATA[
                <idea-plugin package="com.example.inline">
                  <extensions defaultExtensionNs="com.intellij">
                    <applicationService serviceImplementation="com.example.InlineService"/>
                  </extensions>
                </idea-plugin>
              ]]></module>
            </content>
            """,
            attributes = """package="com.example""""
          )
        }
      }
    }
    assertParityOnSuccess(jar)
  }

  @Test
  fun `plugin with components listeners extension points and actions`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor(
            """
            <application-components>
              <component>
                <interface-class>com.example.AppComponentInterface</interface-class>
                <implementation-class>com.example.AppComponent</implementation-class>
              </component>
            </application-components>
            <project-components>
              <component>
                <implementation-class>com.example.ProjectComponent</implementation-class>
              </component>
            </project-components>
            <applicationListeners>
              <listener class="com.example.MyListener" topic="com.example.MyTopic" activeInTestMode="false"/>
            </applicationListeners>
            <projectListeners>
              <listener class="com.example.ProjectListener" topic="com.example.ProjectTopic" os="mac"/>
            </projectListeners>
            <extensionPoints>
              <extensionPoint name="myExtensionPoint" beanClass="com.example.MyBean" dynamic="true"/>
              <extensionPoint qualifiedName="com.example.qualified" interface="com.example.MyInterface" area="IDEA_PROJECT"/>
            </extensionPoints>
            <actions resource-bundle="messages.MyActions">
              <action id="my.action" class="com.example.MyAction" text="My Action">
                <add-to-group group-id="ToolsMenu" anchor="last"/>
                <keyboard-shortcut keymap="${'$'}default" first-keystroke="ctrl alt E"/>
              </action>
              <group id="my.group" popup="true">
                <action id="my.nested.action" class="com.example.NestedAction"/>
                <separator/>
                <reference ref="my.action"/>
              </group>
            </actions>
            <extensions defaultExtensionNs="com.intellij">
              <applicationService serviceInterface="com.example.Iface" serviceImplementation="com.example.Impl"
                                  testServiceImplementation="com.example.TestImpl" overrides="true" client="all" preload="await"/>
              <moduleService serviceImplementation="com.example.ModuleService"/>
              <postStartupActivity implementation="com.example.StartupActivity"/>
              <toolWindow id="MyWindow" anchor="bottom" factoryClass="com.example.Factory" order="first"/>
              <localInspection language="JAVA" shortName="MyInspection" displayName="My Inspection"
                               groupName="Example" enabledByDefault="true" implementationClass="com.example.Inspection">
                <option name="detail" value="true"/>
              </localInspection>
            </extensions>
            <extensions defaultExtensionNs="org.example">
              <customExtension someAttr="value"/>
            </extensions>
            """
          )
        }
      }
    }
    assertParityOnSuccess(jar)
  }

  /**
   * Documented divergence (DESIGN.md, section 5): the legacy path silently drops an
   * `<extensions>` block that carries an `xmlns` attribute (JDOM `getChildren("extensions")`
   * is namespace-sensitive), while the community parser matches elements by local name and
   * registers such extensions, mirroring the IDE runtime.
   */
  @Test
  fun `extensions block with xmlns attribute is dropped by legacy but read by community parser`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor(
            """
            <extensions xmlns="com.third">
              <nsExtension/>
            </extensions>
            """
          )
        }
      }
    }
    val (legacy, community) = parseBothWays(jar)
    val legacyPlugin = (legacy as PluginCreationSuccess).plugin
    val communityPlugin = (community as PluginCreationSuccess).plugin
    assertTrue("legacy path is expected to drop the namespaced extensions block",
      "com.third.nsExtension" !in legacyPlugin.extensions)
    assertTrue("community path is expected to register the namespaced extension",
      "com.third.nsExtension" in communityPlugin.extensions)
  }

  @Test
  fun `plugin with kotlin plugin mode extension`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor(
            """
            <extensions defaultExtensionNs="org.jetbrains.kotlin">
              <supportsKotlinPluginMode supportsK1="false" supportsK2="true"/>
            </extensions>
            """
          )
        }
      }
    }
    assertParityOnSuccess(jar)
  }

  @Test
  fun `plugin with theme extension`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") {
          descriptor(
            """
            <extensions defaultExtensionNs="com.intellij">
              <themeProvider id="my.theme" path="/my-theme.theme.json"/>
            </extensions>
            """
          )
        }
      }
      file("my-theme.theme.json") { """{"name": "my-theme", "dark": true}""" }
    }
    assertParityOnSuccess(jar)
  }

  @Test
  fun `invalid descriptor produces equal validation problems`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        // no <name>, no <vendor>, wildcard since-build
        file("plugin.xml") {
          """
          <idea-plugin>
            <id>com.example.invalid</id>
            <version>1.0</version>
            <idea-version since-build="131.*"/>
            <description>Long enough description of this plugin, long enough indeed.</description>
          </idea-plugin>
          """.trimIndent()
        }
      }
    }
    assertParityOnFailure(jar)
  }

  @Test
  fun `plugin file that is not an archive fails identically`() {
    val notAZip = temporaryFolder.newFile("plugin.zip")
    java.nio.file.Files.write(notAZip, "this is not really a zip file".toByteArray())
    assertParityOnFailure(notAZip)
  }

  @Test
  fun `plugin jar without descriptor fails identically`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("unrelated.txt") { "nothing here" }
      }
    }
    assertParityOnFailure(jar)
  }

  @Test
  fun `plugin with malformed descriptor xml fails identically`() {
    val jar = buildZipFile(temporaryFolder.newFile("plugin.jar")) {
      dir("META-INF") {
        file("plugin.xml") { "<idea-plugin><id>broken" }
      }
    }
    assertParityOnFailure(jar)
  }

  private fun ContentBuilder.descriptorFile(content: String) {
    file("plugin.xml") { content }
  }

  companion object {
    fun descriptor(body: String, attributes: String = ""): String = """
      <idea-plugin $attributes xmlns:xi="http://www.w3.org/2001/XInclude">
        <id>someId</id>
        <name>Some Plugin Name</name>
        <version>1.2.3</version>
        <vendor email="vendor@example.com" url="https://example.com">Example Vendor</vendor>
        <description>Long enough description of this example plugin, long enough indeed.</description>
        <change-notes>Long enough change notes of this example plugin version.</change-notes>
        <idea-version since-build="223.1" until-build="223.*"/>
        ${body.trimIndent()}
      </idea-plugin>
    """.trimIndent()

    val PLAIN_PLUGIN_XML = descriptor(
      """
      <module value="some.alias"/>
      <incompatible-with>incompatible.plugin.id</incompatible-with>
      <depends>com.intellij.modules.platform</depends>
      <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.example.SomeService"/>
      </extensions>
      """
    )
  }
}
