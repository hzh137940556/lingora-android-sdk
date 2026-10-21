// 在 Demo 中显式选择 Maven 包或本地 SDK，远端模式不加载 SDK 源码。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
val sdkMode = providers.gradleProperty("lingoraSdkMode").getOrElse("local")
require(sdkMode in setOf("local", "dev", "release")) {
    "lingoraSdkMode must be local, dev or release"
}
val sdkVersion = providers.gradleProperty("lingoraSdkVersion").orNull
if (sdkMode != "local") {
    require(sdkVersion != null && sdkVersion.matches(Regex("(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*)){2}"))) {
        "Maven mode requires a fixed X.Y.Z lingoraSdkVersion; no automatic source fallback"
    }
} else if (sdkVersion != null) {
    require(sdkVersion.matches(Regex("(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*)){2}(-SNAPSHOT)?"))) {
        "Local lingoraSdkVersion must be X.Y.Z or X.Y.Z-SNAPSHOT"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (sdkMode != "local") maven {
            name = "LingoraGitHubPackages"
            url = uri("https://maven.pkg.github.com/hzh137940556/lingora-android-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
            content { includeGroup("io.lingora") }
        }
    }
}

rootProject.name = "LingoraSdkDemo"
include(":app")

if (sdkMode == "local") {
    val sdkDirectory = file(providers.gradleProperty("lingoraLocalSdkPath").getOrElse("../sdk"))
    require(sdkDirectory.resolve("settings.gradle.kts").isFile &&
        sdkDirectory.resolve("lingora-android-sdk/build.gradle.kts").isFile) {
        "Local SDK not found at $sdkDirectory; set lingoraLocalSdkPath explicitly"
    }
    includeBuild(sdkDirectory) {
        name = "lingora-local-sdk"
        dependencySubstitution {
            substitute(module("io.lingora:lingora-android"))
                .using(project(":lingora-android-sdk"))
            substitute(module("io.lingora:lingora-android-dev"))
                .using(project(":lingora-android-sdk"))
        }
    }
    logger.lifecycle("Lingora SDK mode=local, source=$sdkDirectory")
} else {
    logger.lifecycle("Lingora SDK mode=$sdkMode, version=$sdkVersion, source=GitHub Packages")
}
