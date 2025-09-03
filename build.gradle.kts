import java.io.File
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
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
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
        create("IC", "2025.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    patchPluginXml {
        version = properties("pluginVersion").orNull ?: project.version.toString()
        sinceBuild = properties("pluginSinceBuild").orNull
        untilBuild = properties("pluginUntilBuild").orNull
        updatePluginXml()
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
    signPlugin {
        certificateChainFile.set(file("./secrets/chain.crt"))
        privateKeyFile.set(file("./secrets/private_encrypted.pem"))
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }
    publishPlugin {
        dependsOn("patchChangelog")
        // dependsOn(generateUpdatePluginsXml)
        token = System.getenv("PUBLISH_TOKEN")
        channels = properties("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
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


fun updatePluginXml() {
    val pluginXmlFile = File("src/main/resources/META-INF/plugin.xml")

    val pluginXmlContent = pluginXmlFile.readText()




    pluginXmlFile.writeText(pluginXmlContent)

}
