package com.jetbrains.plugin.structure.intellij.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class DependsPluginDependencyTest {
  @Test
  fun `toString renders all fields and the closing parenthesis`() {
    assertEquals(
      "Depends(com.foo)",
      DependsPluginDependency("com.foo", isOptional = false).toString()
    )
    assertEquals(
      "Depends(com.foo, optional)",
      DependsPluginDependency("com.foo", isOptional = true).toString()
    )
    assertEquals(
      "Depends(com.foo, optional, configFile=opt.xml)",
      DependsPluginDependency("com.foo", isOptional = true, configFile = "opt.xml").toString()
    )
    assertEquals(
      "Depends(com.foo, configFile=opt.xml)",
      DependsPluginDependency("com.foo", isOptional = false, configFile = "opt.xml").toString()
    )
  }
}
