package com.jetbrains.plugin.structure.intellij.plugin

import com.jetbrains.plugin.structure.intellij.plugin.dependencies.DependencyChain
import com.jetbrains.plugin.structure.intellij.problems.OptionalDependencyDescriptorResolutionProblem
import com.jetbrains.plugin.structure.intellij.problems.PluginCreationResultResolver
import com.jetbrains.plugin.structure.intellij.resources.ResourceResolver
import java.nio.file.Path

internal class OptionalDependencyResolver(private val pluginLoader: PluginLoader) {

  fun resolveOptionalDependencies(
    plugin: PluginCreator,
    pluginArtifactPath: Path,
    resourceResolver: ResourceResolver,
    problemResolver: PluginCreationResultResolver
  ) {
    val dependencyChain = DependencyChain()
    plugin.resolveOptionalDependencies(pluginArtifactPath, resourceResolver, problemResolver, dependencyChain)
    dependencyChain.cycles.forEach {
      plugin.registerOptionalDependenciesConfigurationFilesCycleProblem(it)
    }
  }

  /**
   * @receiver plugin whose optional dependencies are resolved (plugin.xml, then someOptional.xml, ...)
   */
  private fun PluginCreator.resolveOptionalDependencies(
    pluginArtifactPath: Path,
    resourceResolver: ResourceResolver,
    problemResolver: PluginCreationResultResolver,
    dependencyChain: DependencyChain
  ) {
    if (!dependencyChain.extend(this)) {
      return
    }
    for ((pluginDependency, configurationFile) in optionalV1DependencyDescriptors) {
      if (dependencyChain.detectCycle(configurationFile)) {
        continue
      }

      val optionalDependencyCreator = pluginLoader.load(pluginArtifactPath, configurationFile,
        false, resourceResolver, parentPlugin = this, problemResolver
      )
      if (optionalDependencyCreator.isSuccess) {
        val optionalPlugin = optionalDependencyCreator.plugin
        plugin.optionalDescriptors += OptionalPluginDescriptor(pluginDependency, optionalPlugin, configurationFile)
        mergeContent(optionalPlugin)
      } else {
        registerProblem(OptionalDependencyDescriptorResolutionProblem(pluginDependency.id, configurationFile, optionalDependencyCreator.resolvedProblems))
      }
      optionalDependencyCreator.resolveOptionalDependencies(
        pluginArtifactPath,
        resourceResolver,
        problemResolver,
        dependencyChain
      )
    }
    dependencyChain.dropLast()
  }

  // A list of distinct pairs, not a Map: the same plugin ID may be declared in several
  // <depends> elements with different config-files, and each descriptor must be resolved.
  // Exact duplicates (same dependency and same config-file) are still resolved only once.
  private val PluginCreator.optionalV1DependencyDescriptors: List<Pair<PluginDependency, String>>
    get() = plugin.dependsList
      .mapNotNull { dep ->
        dep.resolveDescriptorPath()?.let { descriptor ->
          dep.asPluginDependency() to descriptor
        }
      }
      .distinct()
}
