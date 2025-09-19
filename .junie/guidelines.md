LiveLatex – Contributor Guidelines (project‑specific)

Audience: IntelliJ Platform plugin developers familiar with Gradle/Kotlin. This doc captures project‑specific build, test, and development notes that are not obvious from vanilla templates.

1) Build and Configuration
- Toolchain/Versions (locked by Gradle scripts)
  - Java: 21 (sourceCompatibility/targetCompatibility and Kotlin jvmTarget=21).
  - Kotlin: 2.1.0.
  - Gradle: 8.14 (wrapper). Use the provided gradlew/gradlew.bat.
  - IntelliJ Platform Gradle Plugin: 2.5.0 targeting IDE 2025.2.
  - Plugin group: com.omariskandarani, version is driven from gradle.properties (pluginVersion).
- IDE Run/Debug
  - Use the Gradle task: runIde (provided by org.jetbrains.intellij.platform) to launch a sandbox IDE with the plugin.
  - The repo also contains .run/Run IDE with Plugin.run.xml you can use directly from IntelliJ’s Run/Debug configurations.
- Plugin XML and Description
  - patchPluginXml task sets version/since/untilBuild from gradle.properties and injects pluginDescription from README.md between markers:
    <!-- Plugin description --> ... <!-- Plugin description end -->
  - Changing those markers or removing them will break the build (GradleException in patch task).
- Signing/Publishing (optional)
  - signPlugin uses secrets in ./secrets and requires env PRIVATE_KEY_PASSWORD.
  - publishPlugin requires env PUBLISH_TOKEN; channel is derived from pluginVersion suffix.
- Repositories/Dependencies
  - Repos: mavenCentral + IntelliJ default repositories via intellijPlatform.defaultRepositories().
  - Platform dependency is declared via intellijPlatform { create("IC", "2025.2") }.
  - testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform) is enabled for IDE tests.
  - Plain JUnit 4 tests are also supported via testImplementation("junit:junit:4.13.2").

Build commands
- Windows: .\gradlew.bat build
- macOS/Linux: ./gradlew build
- Common tasks: clean, build, test, runIde, patchPluginXml, signPlugin, publishPlugin

2) Testing – how to configure and run
This project supports two complementary styles of tests:
- Plain unit tests (fast, do not require IDE runtime)
  - JUnit 4 is available (testImplementation junit:junit:4.13.2).
  - Place tests under src/test/kotlin.
- IntelliJ Platform tests (heavier, run with IDE test framework)
  - Available through intellijPlatform.testFramework; extend com.intellij.testFramework.LightPlatformTestCase or use test fixtures.

Running tests
- CLI: use the Gradle wrapper
  - Windows: .\gradlew.bat test
  - macOS/Linux: ./gradlew test
- IntelliJ IDEA: use the Gradle tool window (Tasks > verification > test) or right‑click a test class and Run.

Example: adding and executing a simple unit test
- Create src/test/kotlin/com/omariskandarani/livelatex/PlainUnitTest.kt with the content below:
  
  package com.omariskandarani.livelatex
  
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertTrue
  import org.junit.Test
  
  class PlainUnitTest {
      @Test
      fun testLatexHtmlHelperAvailability() {
          val text = "m{a}th & text"
          val html = com.omariskandarani.livelatex.html.htmlEscapeAll(text)
          assertTrue(html.contains("&amp;"))
          assertEquals("m{a}th &amp; text", html)
      }
  }
  
- Run: gradlew test. This should execute quickly and pass. It validates wiring and demonstrates calling a real helper from src/main.

Example: adding a minimal IntelliJ Platform test
- Create src/test/kotlin/com/omariskandarani/livelatex/SimplePlatformTest.kt:
  
  package com.omariskandarani.livelatex
  
  import com.intellij.testFramework.LightPlatformTestCase
  
  class SimplePlatformTest : LightPlatformTestCase() {
      fun testBasic() {
          assertTrue(listOf(1, 2, 3).sum() == 6)
      }
  }
  
- Run with gradlew test as above. Use Platform tests sparingly; they are slower and spin up IDE test infrastructure.

Notes/troubleshooting for tests
- If Gradle reports “No tests found”, ensure:
  - Test classes are under src/test/kotlin and named *Test with methods annotated with @Test (JUnit) or named test* (JUnit 3 style for LightPlatformTestCase).
  - The Gradle daemon picked up the new test files (run gradlew --stop then gradlew clean test).
- If IntelliJ Platform tests are slow or fail to initialize, verify the IDE version in build.gradle.kts (create("IC", "2025.2")) matches a resolvable platform and you have network access to fetch artifacts.
- When mixing plain unit tests and platform tests, keep pure logic isolated in plain unit tests for speed.

3) Additional development information
- Code layout overview
  - com.omariskandarani.livelatex.actions: IDE actions (e.g., inserting images, generating tables, TikZ figure creation dialogs, text formatting actions). These bind into plugin.xml actions.
  - com.omariskandarani.livelatex.core.LatexPreviewService: Core service for live LaTeX preview.
  - com.omariskandarani.livelatex.html.LatexHtml: Large set of top‑level functions for LaTeX→HTML conversion and sanitization. Many helpers are pure and unit‑testable (e.g., htmlEscapeAll, convertItemize, convertEnumerate).
  - com.omariskandarani.livelatex.tables.TableGenerator and ui dialogs for wizards.
  - resources/META‑INF/plugin.xml: Actions, extensions, and plugin metadata.
- Code style
  - Kotlin official style (kotlin.code.style=official in gradle.properties). Keep top‑level helpers small and cohesive; prefer pure functions for conversion logic to ease unit testing.
- Hot spots and pitfalls
  - LatexHtml is long; prefer adding new helpers rather than growing existing ones. Keep conversion functions pure (no file IO) unless necessary; image path resolution already touches filesystem.
  - updatePluginXml() in build.gradle.kts currently just rewrites plugin.xml content unchanged; keep it idempotent if you modify it.
  - patchPluginXml extracts description from README markers; keep them intact.
  - Signing/publishing depends on files in ./secrets. Local development and CI builds do not need these unless you sign/publish.
  - Gradle properties: pluginSinceBuild and pluginUntilBuild are defined in gradle.properties. Ensure they align with plugin.xml sinceBuild/untilBuild if you change IDE targets.
- Running in IDE
  - Prefer runIde for verifying UI/actions. It will build and run against the configured platform version using a sandbox (no risk to your main IDE profile).
- Debugging & logging
  - Use standard IDEA logging via com.intellij.openapi.diagnostic.Logger when adding new platform components.
  - For converter/debug prints in non‑platform code, prefer light, test‑only logging or guarded debug flags to avoid noisy output in production.

Housekeeping for PRs
- Keep README plugin description markers intact.
- If you add tests, you can run them locally with gradlew test. CI should also be able to run them without extra configuration.
- Avoid committing ./secrets content changes unless intended; credentials must not be hard‑coded.

This document is intentionally focused on specifics unique to this repository. For general IntelliJ plugin development, refer to: https://plugins.jetbrains.com/docs/intellij/welcome.html