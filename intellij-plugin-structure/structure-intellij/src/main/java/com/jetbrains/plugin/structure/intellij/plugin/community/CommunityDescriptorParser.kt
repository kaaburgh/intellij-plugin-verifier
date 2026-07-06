/*
 * Copyright 2000-2026 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.intellij.plugin.community

import com.intellij.platform.pluginSystem.parser.impl.PluginDescriptorReaderContext
import com.intellij.platform.pluginSystem.parser.impl.RawPluginDescriptor
import com.intellij.platform.pluginSystem.parser.impl.parsePluginXml
import com.intellij.util.xml.dom.NoOpXmlInterner
import com.intellij.util.xml.dom.XmlInterner
import com.jetbrains.plugin.structure.base.problems.UnableToReadDescriptor
import com.jetbrains.plugin.structure.intellij.plugin.ValidationContext
import com.jetbrains.plugin.structure.intellij.problems.XIncludeResolutionErrors
import com.jetbrains.plugin.structure.intellij.resources.ResourceResolver
import org.jdom2.Document
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path

private val LOG = LoggerFactory.getLogger(CommunityDescriptorParser::class.java)

/**
 * Parses a plugin descriptor with the intellij-community stream parser
 * (`com.intellij.platform.pluginSystem.parser.impl.parsePluginXml`), including inline
 * `<xi:include>` resolution via [ResourceResolverXIncludeLoader].
 *
 * In this vertical slice the descriptor arrives as an already-loaded JDOM [Document]
 * (the loaders are unchanged), so the document is serialized back to bytes first.
 * Parse failures are mapped to the same problem types the legacy path reports.
 */
internal class CommunityDescriptorParser {

  private val readContext = object : PluginDescriptorReaderContext {
    override val interner: XmlInterner = NoOpXmlInterner

    // The legacy XIncluder always fails on unresolvable non-optional includes; mirror that.
    override val isMissingIncludeIgnored: Boolean = false
  }

  fun parse(
    descriptorPath: String,
    pluginFileName: String,
    originalDocument: Document,
    documentPath: Path,
    pathResolver: ResourceResolver,
    validationContext: ValidationContext
  ): RawPluginDescriptor? {
    val descriptorBytes = serialize(originalDocument)
    val xIncludeLoader = ResourceResolverXIncludeLoader(pathResolver, documentPath)
    return try {
      parsePluginXml(descriptorBytes, documentPath.toString(), readContext, xIncludeLoader).build()
    } catch (e: Exception) {
      if (e.isXIncludeFailure()) {
        LOG.info("Unable to resolve <xi:include> elements of descriptor '$descriptorPath' from '$pluginFileName'", e)
        validationContext += XIncludeResolutionErrors(descriptorPath, e.messageWithCauses())
      } else {
        LOG.info("Unable to read plugin descriptor $descriptorPath of $pluginFileName", e)
        validationContext += UnableToReadDescriptor(descriptorPath, e.localizedMessage)
      }
      null
    }
  }

  private fun serialize(document: Document): ByteArray {
    val output = ByteArrayOutputStream()
    XMLOutputter(Format.getRawFormat()).output(document, output)
    return output.toByteArray()
  }

  /**
   * The community parser signals unresolvable includes with untyped exceptions;
   * recognize them by the loader-thrown [IOException] cause or the known message prefixes
   * of `XmlReader.readInclude`.
   */
  private fun Exception.isXIncludeFailure(): Boolean {
    if (generateSequence<Throwable>(this) { it.cause }.any { it is IOException }) {
      return true
    }
    val message = message ?: return false
    return message.startsWith("Cannot resolve ")
      || message.startsWith("Missing `href` attribute")
      || message.startsWith("Attribute `xpointer` is not supported")
      || message.contains("include is not supported")
  }

  private fun Exception.messageWithCauses(): String {
    return generateSequence<Throwable>(this) { it.cause }
      .mapNotNull { it.message }
      .distinct()
      .joinToString(": ")
  }
}
