package com.jetbrains.plugin.structure.intellij.community

import com.jetbrains.plugin.structure.base.plugin.PluginCreationFail
import com.jetbrains.plugin.structure.base.plugin.PluginCreationResult
import com.jetbrains.plugin.structure.base.plugin.PluginCreationSuccess
import com.jetbrains.plugin.structure.intellij.plugin.IdePlugin
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginContentDescriptor
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginImpl
import org.jdom2.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * Deep semantic comparison of two [PluginCreationResult]s produced by the legacy and the
 * community-backed parsing paths.
 *
 * Documented representation-level differences are normalized instead of compared verbatim
 * (see `structure-intellij-community/DESIGN.md`, section 5):
 * * free-text fields are compared after trimming (the community parser nullifies/trims all text),
 * * `underlyingDocument` is not compared (pre- vs post-XInclude JDOM tree),
 * * extension elements are compared by extension point name, attributes, children and text,
 *   but not by the top-level tag name (not retained by the community model).
 */
object ParityAssertions {

  fun assertParity(legacy: PluginCreationResult<IdePlugin>, community: PluginCreationResult<IdePlugin>) {
    when (legacy) {
      is PluginCreationSuccess -> {
        if (community !is PluginCreationSuccess) {
          fail(
            "Legacy path succeeded but community path failed with: " +
              (community as PluginCreationFail).errorsAndWarnings.joinToString()
          )
          return
        }
        assertEquals(
          "warnings differ",
          legacy.warnings.map { it.javaClass.simpleName to it.message }.sortedBy { it.toString() },
          community.warnings.map { it.javaClass.simpleName to it.message }.sortedBy { it.toString() }
        )
        assertEquals(
          "unacceptable warnings differ",
          legacy.unacceptableWarnings.map { it.javaClass.simpleName to it.message }.sortedBy { it.toString() },
          community.unacceptableWarnings.map { it.javaClass.simpleName to it.message }.sortedBy { it.toString() }
        )
        assertPluginParity(legacy.plugin, community.plugin)
      }
      is PluginCreationFail -> {
        if (community !is PluginCreationFail) {
          fail("Legacy path failed (${legacy.errorsAndWarnings.joinToString()}) but community path succeeded")
          return
        }
        // Parse-level failures produce differently-worded messages; compare problem types.
        assertEquals(
          "failure problem types differ (legacy=${legacy.errorsAndWarnings.joinToString()}, " +
            "community=${community.errorsAndWarnings.joinToString()})",
          legacy.errorsAndWarnings.map { it.javaClass.simpleName }.sorted(),
          community.errorsAndWarnings.map { it.javaClass.simpleName }.sorted()
        )
      }
    }
  }

  fun assertPluginParity(legacy: IdePlugin, community: IdePlugin) {
    assertEquals("pluginId", legacy.pluginId, community.pluginId)
    assertEquals("pluginName", legacy.pluginName, community.pluginName)
    assertEquals("pluginVersion", legacy.pluginVersion, community.pluginVersion)
    assertEquals("sinceBuild", legacy.sinceBuild, community.sinceBuild)
    assertEquals("untilBuild", legacy.untilBuild, community.untilBuild)
    assertEquals("url", legacy.url, community.url)
    assertEquals("vendor", legacy.vendor, community.vendor)
    assertEquals("vendorEmail", legacy.vendorEmail, community.vendorEmail)
    assertEquals("vendorUrl", legacy.vendorUrl, community.vendorUrl)
    assertEquals("description", legacy.description?.trim()?.ifEmpty { null }, community.description?.trim()?.ifEmpty { null })
    assertEquals("changeNotes", legacy.changeNotes?.trim()?.ifEmpty { null }, community.changeNotes?.trim()?.ifEmpty { null })
    assertEquals("productDescriptor", legacy.productDescriptor, community.productDescriptor)
    assertEquals("useIdeClassLoader", legacy.useIdeClassLoader, community.useIdeClassLoader)
    assertEquals("isImplementationDetail", legacy.isImplementationDetail, community.isImplementationDetail)
    assertEquals("hasPackagePrefix", legacy.hasPackagePrefix, community.hasPackagePrefix)
    assertEquals("moduleVisibility", legacy.moduleVisibility, community.moduleVisibility)
    assertEquals("kotlinPluginMode", legacy.kotlinPluginMode.toString(), community.kotlinPluginMode.toString())
    assertEquals("pluginAliases", legacy.pluginAliases, community.pluginAliases)
    @Suppress("DEPRECATION")
    assertEquals("definedModules", legacy.definedModules, community.definedModules)
    assertEquals("incompatibleWith", legacy.incompatibleWith, community.incompatibleWith)

    assertEquals(
      "dependencies",
      legacy.dependencies.map { it.javaClass.simpleName to it.id },
      community.dependencies.map { it.javaClass.simpleName to it.id }
    )
    assertEquals(
      "dependsList",
      legacy.dependsList.map { Triple(it.pluginId, it.isOptional, it.configFile) },
      community.dependsList.map { Triple(it.pluginId, it.isOptional, it.configFile) }
    )
    assertEquals(
      "contentModuleDependencies",
      legacy.contentModuleDependencies.map { it.moduleName to it.namespace },
      community.contentModuleDependencies.map { it.moduleName to it.namespace }
    )
    assertEquals(
      "pluginMainModuleDependencies",
      legacy.pluginMainModuleDependencies.map { it.pluginId },
      community.pluginMainModuleDependencies.map { it.pluginId }
    )
    assertEquals("contentModules", legacy.contentModules, community.contentModules)

    assertEquals("extension point names", legacy.extensions.keys.sorted(), community.extensions.keys.sorted())
    for ((epName, legacyElements) in legacy.extensions) {
      val communityElements = community.extensions[epName]!!
      assertEquals(
        "extension elements of '$epName'",
        legacyElements.map { it.signature(includeName = false) }.sorted(),
        communityElements.map { it.signature(includeName = false) }.sorted()
      )
    }

    assertContainerParity("app", legacy.appContainerDescriptor, community.appContainerDescriptor)
    assertContainerParity("project", legacy.projectContainerDescriptor, community.projectContainerDescriptor)
    assertContainerParity("module", legacy.moduleContainerDescriptor, community.moduleContainerDescriptor)

    if (legacy is IdePluginImpl && community is IdePluginImpl) {
      assertEquals(
        "actions",
        legacy.actions.map { it.signature(includeName = true) }.sorted(),
        community.actions.map { it.signature(includeName = true) }.sorted()
      )
    }

    assertEquals("declaredThemes", legacy.declaredThemes, community.declaredThemes)
    assertEquals("icons", legacy.icons.map { it.theme to it.fileName }, community.icons.map { it.theme to it.fileName })

    assertEquals(
      "optionalDescriptors config files",
      legacy.optionalDescriptors.map { it.configurationFilePath },
      community.optionalDescriptors.map { it.configurationFilePath }
    )
    legacy.optionalDescriptors.zip(community.optionalDescriptors).forEach { (legacyOptional, communityOptional) ->
      assertPluginParity(legacyOptional.optionalPlugin, communityOptional.optionalPlugin)
    }

    assertEquals(
      "modulesDescriptors names",
      legacy.modulesDescriptors.map { it.name }.sorted(),
      community.modulesDescriptors.map { it.name }.sorted()
    )
  }

  private fun assertContainerParity(
    scope: String,
    legacy: IdePluginContentDescriptor,
    community: IdePluginContentDescriptor
  ) {
    assertEquals("$scope services", legacy.services.sortedBy { it.toString() }, community.services.sortedBy { it.toString() })
    assertEquals("$scope components", legacy.components.sortedBy { it.toString() }, community.components.sortedBy { it.toString() })
    assertEquals("$scope listeners", legacy.listeners.sortedBy { it.toString() }, community.listeners.sortedBy { it.toString() })
    assertEquals("$scope extensionPoints", legacy.extensionPoints.sortedBy { it.toString() }, community.extensionPoints.sortedBy { it.toString() })
  }

  /**
   * A canonical, name-optional representation of a JDOM element for semantic comparison.
   */
  private fun Element.signature(includeName: Boolean): String = buildString {
    if (includeName) {
      append(name)
    }
    append('[')
    append(attributes.sortedBy { it.name }.joinToString(",") { "${it.name}=${it.value}" })
    append(']')
    val text = textTrim
    if (text.isNotEmpty()) {
      append("text=").append(text)
    }
    append('(')
    append(children.joinToString(",") { it.signature(includeName = true) })
    append(')')
  }
}
