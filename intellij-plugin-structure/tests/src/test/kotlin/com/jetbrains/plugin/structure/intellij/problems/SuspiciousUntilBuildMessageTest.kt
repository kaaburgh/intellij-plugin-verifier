package com.jetbrains.plugin.structure.intellij.problems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspiciousUntilBuildMessageTest {
  private val recommendation = "If you want your plugin to be compatible with all future IDE versions, " +
    "you can remove this attribute. However, we highly recommend setting it to the latest available IDE version."

  @Test
  fun `message without additional details ends with the recommendation`() {
    val message = SuspiciousUntilBuild("291.1").message
    assertEquals(
      "The <until-build> '291.1' does not represent the actual build number. $recommendation",
      message
    )
  }

  @Test
  fun `message keeps both the additional details and the recommendation`() {
    val message = NonexistentReleaseInUntilBuild("294.1", "2029.4").message
    assertTrue(
      "Expected additional details in: $message",
      message.contains("Version '2029.4' does not exist")
    )
    assertTrue(
      "Expected the recommendation to be preserved in: $message",
      message.contains(recommendation)
    )
  }
}
