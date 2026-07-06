import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

dependencies {
  api(sharedLibs.jetbrains.annotations)
  api(libs.aalto.xml)

  implementation(sharedLibs.slf4j.api)
}

kotlin {
  compilerOptions {
    // The vendored intellij-community sources require a modern language level
    // (e.g. enum 'entries'), unlike the rest of this project which targets 1.4.
    apiVersion = KotlinVersion.KOTLIN_2_0
    languageVersion = KotlinVersion.KOTLIN_2_0
    // XmlReader.kt uses 'when' guards
    freeCompilerArgs.add("-Xwhen-guards")
  }
}
