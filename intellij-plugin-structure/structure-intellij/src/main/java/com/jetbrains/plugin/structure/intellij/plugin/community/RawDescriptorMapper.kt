/*
 * Copyright 2000-2026 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.intellij.plugin.community

import com.intellij.platform.pluginSystem.parser.impl.RawPluginDescriptor
import com.intellij.platform.pluginSystem.parser.impl.ScopedElementsContainer
import com.intellij.platform.pluginSystem.parser.impl.elements.ClientKindValue
import com.intellij.platform.pluginSystem.parser.impl.elements.DependenciesElement
import com.intellij.platform.pluginSystem.parser.impl.elements.ExtensionElement
import com.intellij.platform.pluginSystem.parser.impl.elements.ModuleLoadingRuleValue
import com.intellij.platform.pluginSystem.parser.impl.elements.ModuleVisibilityValue
import com.intellij.platform.pluginSystem.parser.impl.elements.OSValue
import com.intellij.platform.pluginSystem.parser.impl.elements.PreloadModeValue
import com.intellij.platform.pluginSystem.parser.impl.elements.xmlValue
import com.intellij.util.xml.dom.XmlElement
import com.jetbrains.plugin.structure.intellij.beans.ContentModuleDependencyBean
import com.jetbrains.plugin.structure.intellij.beans.IdeaVersionBean
import com.jetbrains.plugin.structure.intellij.beans.PluginBean
import com.jetbrains.plugin.structure.intellij.beans.PluginContentBean
import com.jetbrains.plugin.structure.intellij.beans.PluginDependenciesBean
import com.jetbrains.plugin.structure.intellij.beans.PluginDependenciesPluginBean
import com.jetbrains.plugin.structure.intellij.beans.PluginDependencyBean
import com.jetbrains.plugin.structure.intellij.beans.PluginModuleBean
import com.jetbrains.plugin.structure.intellij.beans.PluginVendorBean
import com.jetbrains.plugin.structure.intellij.beans.ProductDescriptorBean
import com.jetbrains.plugin.structure.intellij.plugin.ContentModuleDependency
import com.jetbrains.plugin.structure.intellij.plugin.DependsPluginDependency
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginContentDescriptor
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginImpl
import com.jetbrains.plugin.structure.intellij.plugin.KotlinPluginMode
import com.jetbrains.plugin.structure.intellij.plugin.Module
import com.jetbrains.plugin.structure.intellij.plugin.ModuleLoadingRule
import com.jetbrains.plugin.structure.intellij.plugin.ModuleVisibility
import com.jetbrains.plugin.structure.intellij.plugin.MutableIdePluginContentDescriptor
import com.jetbrains.plugin.structure.intellij.plugin.PluginCreator
import com.jetbrains.plugin.structure.intellij.plugin.PluginMainModuleDependency
import com.jetbrains.plugin.structure.intellij.plugin.PluginV1Dependency
import com.jetbrains.plugin.structure.intellij.plugin.PluginV2Dependency
import com.jetbrains.plugin.structure.intellij.plugin.ModuleV2Dependency
import com.jetbrains.plugin.structure.intellij.plugin.ProductDescriptor
import com.jetbrains.plugin.structure.intellij.verifiers.ProblemRegistrar
import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import com.jetbrains.plugin.structure.intellij.version.ProductReleaseVersion
import org.jdom2.Element
import java.time.format.DateTimeFormatter

private val RELEASE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

private const val KOTLIN_PLUGIN_MODE_EP = "org.jetbrains.kotlin.supportsKotlinPluginMode"

/**
 * Maps the intellij-community [RawPluginDescriptor] onto the verifier's model:
 *
 * * [toPluginBean] produces a [PluginBean] equivalent to what the JAXB extractor yields, so that
 *   the unchanged `PluginBeanValidator` performs the same validations on the community path;
 * * [convert] populates [IdePluginImpl], mirroring `PluginBeanToIdePluginConverter` semantics.
 *
 * Known information losses of the community model (documented in
 * `structure-intellij-community/DESIGN.md`, section 5): tri-state `optional` of `<depends>`,
 * the `eap` attribute of `<product-descriptor>`, original tag names of extension elements
 * declared with a `point` attribute, and raw (unparseable) values of `release-date`
 * and `release-version`.
 */
internal class RawDescriptorMapper {

  fun toPluginBean(raw: RawPluginDescriptor): PluginBean {
    val bean = PluginBean()
    bean.id = raw.id
    bean.name = raw.name
    bean.pluginVersion = raw.version
    bean.description = raw.description
    bean.changeNotes = raw.changeNotes
    bean.category = raw.category
    bean.resourceBundle = raw.resourceBundleBaseName
    bean.url = raw.url ?: ""
    bean.packageName = raw.`package`
    bean.useIdeaClassLoader = raw.isUseIdeaClassLoader
    bean.implementationDetail = raw.isImplementationDetail
    bean.allowBundledUpdate = raw.isBundledUpdateAllowed
    bean.visibility = raw.moduleVisibility.toXmlValue()

    if (raw.vendor != null || raw.vendorEmail != null || raw.vendorUrl != null) {
      bean.vendor = PluginVendorBean().apply {
        name = raw.vendor
        url = raw.vendorUrl ?: ""
        email = raw.vendorEmail ?: ""
      }
    }

    if (raw.sinceBuild != null || raw.untilBuild != null) {
      bean.ideaVersion = IdeaVersionBean().apply {
        sinceBuild = raw.sinceBuild
        untilBuild = raw.untilBuild
      }
    }

    if (raw.productCode != null || raw.releaseDate != null || raw.releaseVersion != 0) {
      bean.productDescriptor = ProductDescriptorBean().apply {
        code = raw.productCode
        releaseDate = raw.releaseDate?.format(RELEASE_DATE_FORMATTER)
        releaseVersion = raw.releaseVersion.takeIf { it != 0 }?.toString()
        // The community parser does not retain the 'eap' attribute; see DESIGN.md#5.
        eap = null
        optional = if (raw.isLicenseOptional) "true" else null
      }
    }

    bean.dependencies = raw.depends.mapTo(ArrayList()) { depends ->
      PluginDependencyBean().apply {
        dependencyId = depends.pluginId
        configFile = depends.configFile
        // The community model cannot distinguish an explicit optional="false" from an absent
        // attribute; map non-optional to an absent attribute. See DESIGN.md#5.
        optional = if (depends.isOptional) true else null
      }
    }

    if (raw.dependencies.isNotEmpty()) {
      bean.dependenciesV2 = listOf(PluginDependenciesBean().apply {
        modules = raw.dependencies.filterIsInstance<DependenciesElement.ModuleDependency>()
          .mapTo(ArrayList()) { dependency ->
            ContentModuleDependencyBean().apply {
              moduleName = dependency.moduleName
              namespace = dependency.namespace
            }
          }
        plugins = raw.dependencies.filterIsInstance<DependenciesElement.PluginDependency>()
          .mapTo(ArrayList()) { dependency ->
            PluginDependenciesPluginBean().apply { dependencyId = dependency.pluginId }
          }
      })
    }

    // The community parser flattens all <content> tags; group consecutive modules that share
    // a namespace back into content beans to preserve the legacy namespace defaulting.
    bean.pluginContent = raw.contentModules
      .groupConsecutiveBy { it.namespace }
      .mapTo(ArrayList()) { (namespace, modules) ->
        PluginContentBean().apply {
          this.namespace = namespace
          this.modules = modules.mapTo(ArrayList()) { module ->
            PluginModuleBean().apply {
              moduleName = module.name
              loadingRule = module.loadingRule.xmlValue
              value = module.embeddedDescriptorContent?.let { String(it) }
            }
          }
        }
      }

    bean.incompatibleWith = raw.incompatibleWith.toMutableList()
    bean.pluginAliases = raw.pluginAliases.toMutableList()
    return bean
  }

  fun convert(
    raw: RawPluginDescriptor,
    parentPlugin: PluginCreator?,
    problemRegistrar: ProblemRegistrar,
    targetPlugin: IdePluginImpl
  ) {
    targetPlugin.apply {
      pluginName = raw.name?.trim()
      pluginId = raw.id?.trim() ?: pluginName
      url = raw.url?.trim() ?: ""
      pluginVersion = raw.version?.trim()
      raw.pluginAliases.forEach { addPluginAlias(it) }
      useIdeClassLoader = raw.isUseIdeaClassLoader
      isImplementationDetail = raw.isImplementationDetail

      sinceBuild = raw.sinceBuild?.let { IdeVersion.createIdeVersion(it) }
      untilBuild = raw.untilBuild?.takeIf { it.isNotEmpty() }?.let { untilBuild ->
        val resolvedUntilBuild = if (untilBuild.endsWith(".*")) {
          untilBuild.substringBeforeLast('.') + ".${Int.MAX_VALUE}"
        } else {
          untilBuild
        }
        IdeVersion.createIdeVersion(resolvedUntilBuild)
      }

      hasPackagePrefix = raw.`package` != null
      moduleVisibility = when (raw.moduleVisibility) {
        ModuleVisibilityValue.PUBLIC -> ModuleVisibility.PUBLIC
        ModuleVisibilityValue.INTERNAL -> ModuleVisibility.INTERNAL
        ModuleVisibilityValue.PRIVATE -> ModuleVisibility.PRIVATE
      }

      // dependencies from `<depends>`
      raw.depends.forEach { depends ->
        addDepends(DependsPluginDependency(depends.pluginId, depends.isOptional, depends.configFile))
        dependencies += if (depends.isOptional) {
          PluginV1Dependency.Optional(depends.pluginId)
        } else {
          PluginV1Dependency.Mandatory(depends.pluginId)
        }
      }

      // dependencies from `<dependencies>`; the legacy converter processes all module
      // dependencies first and all plugin dependencies afterwards — keep that order
      val moduleDependencies = raw.dependencies.filterIsInstance<DependenciesElement.ModuleDependency>()
      val pluginDependencies = raw.dependencies.filterIsInstance<DependenciesElement.PluginDependency>()
      moduleDependencies.forEach { dependency ->
        addContentModuleDependency(
          ContentModuleDependency(dependency.moduleName, dependency.resolveNamespace(parentPlugin))
        )
      }
      dependencies += moduleDependencies.map { ModuleV2Dependency(it.moduleName) }
      pluginDependencies.forEach { dependency ->
        addPluginMainModuleDependency(PluginMainModuleDependency(dependency.pluginId))
      }
      dependencies += pluginDependencies.map { PluginV2Dependency(it.pluginId) }

      contentModules += raw.toContentModules()

      incompatibleWith += raw.incompatibleWith

      raw.vendor?.let { vendor = it.trim { c -> c <= ' ' } }
      if (raw.vendor != null || raw.vendorEmail != null || raw.vendorUrl != null) {
        vendorUrl = raw.vendorUrl ?: ""
        vendorEmail = raw.vendorEmail ?: ""
      }

      if (raw.productCode != null && raw.releaseDate != null) {
        productDescriptor = ProductDescriptor(
          raw.productCode!!,
          raw.releaseDate!!,
          ProductReleaseVersion(raw.releaseVersion),
          // 'eap' is not retained by the community parser; see DESIGN.md#5.
          eap = false,
          optional = raw.isLicenseOptional
        )
      }

      changeNotes = raw.changeNotes
      description = raw.description

      actions += raw.actions.map { it.element.toJdom() }

      readExtensions(raw, this)
      readContainer(raw.appElementsContainer, appContainerDescriptor, IdePluginContentDescriptor.ServiceType.APPLICATION, IdePluginContentDescriptor.ListenerType.APPLICATION, this, parentPlugin)
      readContainer(raw.projectElementsContainer, projectContainerDescriptor, IdePluginContentDescriptor.ServiceType.PROJECT, IdePluginContentDescriptor.ListenerType.PROJECT, this, parentPlugin)
      readContainer(raw.moduleElementsContainer, moduleContainerDescriptor, IdePluginContentDescriptor.ServiceType.MODULE, IdePluginContentDescriptor.ListenerType.APPLICATION, this, parentPlugin)
    }
  }

  private fun RawPluginDescriptor.toContentModules(): List<Module> {
    return contentModules.map { module ->
      val namespace = module.namespace
      // if the namespace isn't specified explicitly, a synthetic namespace is used
      // in dependencies between private modules of the plugin (mirrors PluginModuleResolver)
      val actualNamespace = namespace ?: "${id}_\$implicit"
      val loadingRule = ModuleLoadingRule.create(module.loadingRule.xmlValue)
      val textContent = module.embeddedDescriptorContent?.let { String(it) }
      if (textContent.isNullOrBlank()) {
        val configFile = "../${module.name.replace("/", ".")}.xml"
        Module.FileBasedModule(module.name, namespace, actualNamespace, loadingRule, configFile)
      } else {
        Module.InlineModule(module.name, namespace, actualNamespace, loadingRule, textContent)
      }
    }
  }

  private fun readExtensions(raw: RawPluginDescriptor, plugin: IdePluginImpl) {
    raw.extensions.forEach { (epName, extensionElements) ->
      extensionElements.forEach { extensionElement ->
        val jdomElement = extensionElement.toJdom(epName)
        plugin.extensions.getOrPut(epName) { arrayListOf() }.add(jdomElement)
        if (epName == KOTLIN_PLUGIN_MODE_EP) {
          val supportsK1 = jdomElement.getAttributeValue("supportsK1")?.toBoolean() ?: true
          val supportsK2 = jdomElement.getAttributeValue("supportsK2")?.toBoolean() ?: false
          plugin.kotlinPluginMode = KotlinPluginMode.parse(supportsK1, supportsK2)
        }
      }
    }
  }

  private fun readContainer(
    container: ScopedElementsContainer,
    descriptor: MutableIdePluginContentDescriptor,
    serviceType: IdePluginContentDescriptor.ServiceType,
    listenerType: IdePluginContentDescriptor.ListenerType,
    plugin: IdePluginImpl,
    parentPlugin: PluginCreator?
  ) {
    container.services.forEach { service ->
      descriptor.services += IdePluginContentDescriptor.ServiceDescriptor(
        service.serviceInterface,
        service.serviceImplementation,
        serviceType,
        service.testServiceImplementation,
        service.headlessImplementation,
        service.overrides,
        service.configurationSchemaKey,
        service.preload.toPreloadMode(),
        service.client?.toClientKind(),
        // the legacy converter never extracts the service 'os' attribute; mirror it (DESIGN.md#5)
        os = null
      )
    }
    container.components.forEach { component ->
      val implementationClass = component.implementationClass
      if (implementationClass != null) {
        descriptor.components += IdePluginContentDescriptor.ComponentConfig(component.interfaceClass, implementationClass)
      }
    }
    container.listeners.forEach { listener ->
      descriptor.listeners += IdePluginContentDescriptor.ListenerDescriptor(
        listener.topicClassName,
        listener.listenerClassName,
        listenerType,
        listener.activeInTestMode,
        listener.activeInHeadlessMode,
        listener.os?.toOs()
      )
    }
    container.extensionPoints.forEach { extensionPoint ->
      val extensionPointName = extensionPoint.qualifiedName
        ?: extensionPoint.name?.let { name ->
          (plugin.pluginId ?: parentPlugin?.pluginId)?.let { pluginId -> "$pluginId.$name" }
        }
      if (extensionPointName != null) {
        descriptor.extensionPoints += IdePluginContentDescriptor.ExtensionPoint(extensionPointName, extensionPoint.isDynamic)
      }
    }
  }

  private fun DependenciesElement.ModuleDependency.resolveNamespace(parentPlugin: PluginCreator?): String {
    return namespace
      ?: parentPlugin?.plugin?.contentModules
        ?.find { it.name == moduleName }
        ?.actualNamespace ?: "jetbrains"
  }

  private fun ModuleVisibilityValue.toXmlValue(): String? = when (this) {
    ModuleVisibilityValue.PUBLIC -> "public"
    ModuleVisibilityValue.INTERNAL -> "internal"
    ModuleVisibilityValue.PRIVATE -> null
  }

  private fun PreloadModeValue.toPreloadMode(): IdePluginContentDescriptor.PreloadMode = when (this) {
    PreloadModeValue.TRUE -> IdePluginContentDescriptor.PreloadMode.TRUE
    PreloadModeValue.FALSE -> IdePluginContentDescriptor.PreloadMode.FALSE
    PreloadModeValue.AWAIT -> IdePluginContentDescriptor.PreloadMode.AWAIT
    PreloadModeValue.NOT_HEADLESS -> IdePluginContentDescriptor.PreloadMode.NOT_HEADLESS
    PreloadModeValue.NOT_LIGHT_EDIT -> IdePluginContentDescriptor.PreloadMode.NOT_LIGHT_EDIT
  }

  private fun ClientKindValue.toClientKind(): IdePluginContentDescriptor.ClientKind = when (this) {
    ClientKindValue.LOCAL -> IdePluginContentDescriptor.ClientKind.LOCAL
    ClientKindValue.FRONTEND -> IdePluginContentDescriptor.ClientKind.FRONTEND
    ClientKindValue.CONTROLLER -> IdePluginContentDescriptor.ClientKind.CONTROLLER
    ClientKindValue.GUEST -> IdePluginContentDescriptor.ClientKind.GUEST
    ClientKindValue.OWNER -> IdePluginContentDescriptor.ClientKind.OWNER
    ClientKindValue.REMOTE -> IdePluginContentDescriptor.ClientKind.REMOTE
    ClientKindValue.ALL -> IdePluginContentDescriptor.ClientKind.ALL
  }

  private fun OSValue.toOs(): IdePluginContentDescriptor.Os = when (this) {
    OSValue.MAC -> IdePluginContentDescriptor.Os.mac
    OSValue.LINUX -> IdePluginContentDescriptor.Os.linux
    OSValue.WINDOWS -> IdePluginContentDescriptor.Os.windows
    OSValue.UNIX -> IdePluginContentDescriptor.Os.unix
    OSValue.FREEBSD -> IdePluginContentDescriptor.Os.freebsd
  }

  private fun OSValue.toXmlValue(): String = when (this) {
    OSValue.MAC -> "mac"
    OSValue.LINUX -> "linux"
    OSValue.WINDOWS -> "windows"
    OSValue.UNIX -> "unix"
    OSValue.FREEBSD -> "freebsd"
  }

  /**
   * Rebuilds a JDOM element from the community [ExtensionElement].
   *
   * The community model does not retain the original tag name of extension elements
   * (only the extension point FQN); the last segment of the EP name is used instead.
   * When the parser dropped the element body (no attributes and no children, or the
   * `postStartupActivity` EP), a placeholder element carrying the typed attributes
   * is produced so that extension counts stay identical to the legacy path.
   */
  private fun ExtensionElement.toJdom(epName: String): Element {
    val fallbackName = epName.substringAfterLast('.').ifEmpty { "extension" }
    val element = this.element
    if (element != null) {
      return element.toJdom(fallbackName)
    }
    return Element(fallbackName).also {
      implementation?.let { value -> it.setAttribute("implementation", value) }
      orderId?.let { value -> it.setAttribute("id", value) }
      order?.let { value -> it.setAttribute("order", value) }
      os?.let { value -> it.setAttribute("os", value.toXmlValue()) }
    }
  }

  private fun XmlElement.toJdom(fallbackName: String? = null): Element {
    val elementName = name.ifEmpty { fallbackName ?: "extension" }
    val result = Element(elementName)
    attributes.forEach { (attrName, attrValue) -> result.setAttribute(attrName, attrValue) }
    content?.let { result.text = it }
    children.forEach { child -> result.addContent(child.toJdom()) }
    return result
  }

  private inline fun <T, K> List<T>.groupConsecutiveBy(keySelector: (T) -> K): List<Pair<K, List<T>>> {
    val result = mutableListOf<Pair<K, MutableList<T>>>()
    for (item in this) {
      val key = keySelector(item)
      val last = result.lastOrNull()
      if (last != null && last.first == key) {
        last.second.add(item)
      } else {
        result.add(key to mutableListOf(item))
      }
    }
    return result
  }
}
