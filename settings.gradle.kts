import kotlin.system.exitProcess

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "EngineHub Repository"
            url = uri("https://maven.enginehub.org/repo/")
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositories {
        maven {
            name = "EngineHub Repository"
            url = uri("https://maven.enginehub.org/repo/")
            content {
                excludeGroup("net.kyori")
            }
        }
        mavenCentral()
    }
}

if (!File("$rootDir/.git").exists()) {
    logger.lifecycle("""
    **************************************************************************************
    You need to fork and clone this repository! Don't download a .zip file.
    If you need assistance, consult the GitHub docs: https://docs.github.com/get-started/quickstart/fork-a-repo
    **************************************************************************************
    """.trimIndent()
    ).also { exitProcess(1) }
}

logger.lifecycle("""
*******************************************
 You are building FastAsyncWorldEdit!

 If you encounter trouble:
 1) Read COMPILING.adoc if you haven't yet
 2) Try running 'build' in a separate Gradle run
 3) Use gradlew and not gradle
 4) If you still need help, ask on Discord! https://discord.gg/intellectualsites

 Output files will be in [subproject]/build/libs
*******************************************
""")

rootProject.name = "FastAsyncWorldEdit"

includeBuild("build-logic")

include("worldedit-libs")

/*
 * This fork targets Folia stable 26.1.2 exclusively, so only the matching adapter module is built.
 *
 * Upstream FAWE ships one adapter per supported Minecraft version. On Folia those older adapters are
 * not merely unnecessary but actively wrong: Folia's server internals (regioniser, Moonrise chunk
 * system, region ownership checks) differ from Paper's, so NMS code written against a Paper 1.21.x
 * bundle would not be region-safe even if it compiled. Restricting the matrix keeps the build honest.
 */
include("worldedit-bukkit:adapters:adapter-26.1")

listOf("bukkit", "core", "cli").forEach {
    include("worldedit-libs:$it")
    include("worldedit-$it")
}
include("worldedit-libs:core:ap")


// enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
