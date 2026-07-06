dependencies {
  api(project(":structure-base"))
  api(libs.jdom)

  // Experimental: vendored intellij-community descriptor parser (see structure-intellij-community/DESIGN.md)
  implementation(project(":structure-intellij-community"))

  implementation(libs.jaxb.api)
  implementation(libs.jaxb.runtime)

  testImplementation(sharedLibs.junit)
  testImplementation(sharedLibs.mockk)
  testImplementation(libs.commons.compress)
}
