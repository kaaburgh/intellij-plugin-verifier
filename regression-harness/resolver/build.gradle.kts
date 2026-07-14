// Standalone helper build for the compatibility regression harness.
// Resolves the full runtime classpath of an arbitrary structure-intellij artifact
// (from Maven Local or Maven Central) and writes it to a text file, one entry per line.
//
// Usage (from the repository root):
//   ./gradlew -p regression-harness/resolver writeClasspath \
//     -PimplGav=org.jetbrains.intellij.plugins:structure-intellij:3.331 \
//     -PoutFile=/abs/path/classpath.txt

repositories {
  mavenLocal()
  mavenCentral()
  maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

val impl: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies {
  val implGav = providers.gradleProperty("implGav").orNull
    ?: throw InvalidUserDataException("Pass -PimplGav=group:artifact:version")
  impl(implGav)
}

tasks.register("writeClasspath") {
  val classpath = impl
  val outFile = providers.gradleProperty("outFile").orNull
    ?: throw InvalidUserDataException("Pass -PoutFile=/abs/path/classpath.txt")
  doLast {
    val entries = classpath.files.map { it.absolutePath }.sorted()
    File(outFile).apply { parentFile?.mkdirs() }.writeText(entries.joinToString("\n") + "\n")
  }
}
