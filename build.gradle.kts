import kotlin.text.substringAfter
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML

interface Injected {
    @get:Inject val fs: FileSystemOperations
}
val injected = project.objects.newInstance<Injected>()
fun properties(key: String) = providers.gradleProperty(key)

val pluginVersion: String by project

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.changelog") version "2.2.0"
}

group = "com.omariskandarani"
version = properties("pluginVersion").orNull ?: project.version.toString()


repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(properties("platformVersion"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        // 2026.2+: JCEF is a separate bundled plugin/module — required to compile JBCefBrowser
        bundledPlugin("com.intellij.modules.jcef")
        // Always install TeXiFy in the runIde sandbox (edit .tex while testing LiveLatex).
        // Not a <depends> in plugin.xml — end users are not required to install TeXiFy.
        compatiblePlugin("nl.rubensten.texifyidea")
    }
    // Ensure plain JUnit 4 tests can run without relying on platform base classes
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        changeNotes = """
            Initial version
        """.trimIndent()
    }
    // Configure on the extension (not only the task) so PRIVATE_KEY / CERTIFICATE_CHAIN env vars
    // cannot override the files — text PEMs take precedence in SignPluginTask and break IntelliJ runs
    // when run configs store PEMs with spaces instead of newlines.
    signing {
        certificateChainFile.set(layout.projectDirectory.file("secrets/chain.crt"))
        privateKeyFile.set(layout.projectDirectory.file("secrets/private_encrypted.pem"))
        password.set(
            providers.environmentVariable("PRIVATE_KEY_PASSWORD")
                .filter { it.isNotBlank() }
                .orElse(
                    providers.fileContents(layout.projectDirectory.file("secrets/privpass.txt")).asText
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                )
        )
    }
}

tasks {
    patchPluginXml {
        version = properties("pluginVersion").orNull ?: project.version.toString()
        sinceBuild = properties("pluginSinceBuild").orNull
        untilBuild = properties("pluginUntilBuild").orNull
        updatePluginXml(project)
        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription.set(
            file("README.md").readText().lines().run {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end))
            }.joinToString("\n").let { markdownToHTML(it) }
        )

    }
    publishPlugin {
        dependsOn("patchChangelog")
        // dependsOn(generateUpdatePluginsXml)
        token = System.getenv("PUBLISH_TOKEN")
        channels = properties("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    // IntelliJ Platform 2026.2 is built with Java 25 bytecode
    withType<JavaCompile> {
        sourceCompatibility = "25"
        targetCompatibility = "25"
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        // Avoid Kotlin generating ToolWindowFactory default-method bridges (isApplicable, getAnchor, …)
        // that the Marketplace Plugin Verifier flags as deprecated/internal API usages.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.register<Jar>("debugJar") {
    archiveClassifier.set("debug")
    from(sourceSets.main.get().output)
    manifest {
        attributes["Implementation-Title"] = "LiveLatex Debug"
        attributes["Implementation-Version"] = version
    }
}

tasks.register<Jar>("releaseJar") {
    archiveClassifier.set("release")
    from(sourceSets.main.get().output)
    manifest {
        attributes["Implementation-Title"] = "LiveLatex Release"
        attributes["Implementation-Version"] = version
    }
    // Example: Add obfuscation/minification here if needed
}


fun updatePluginXml(project: org.gradle.api.Project) {
    val pluginXmlFile = project.layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml").asFile
    if (!pluginXmlFile.isFile) return
    val pluginXmlContent = pluginXmlFile.readText()
    pluginXmlFile.writeText(pluginXmlContent)
}