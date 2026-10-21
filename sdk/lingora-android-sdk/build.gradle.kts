// 配置 SDK 的版本、渠道和独立 Maven 发布坐标。
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

val sdkVersion = providers.gradleProperty("VERSION_NAME")
    .getOrElse("0.1.0-SNAPSHOT")
val sdkChannel = providers.gradleProperty("SDK_CHANNEL").getOrElse("release")
require(sdkChannel in setOf("dev", "release")) {
    "SDK_CHANNEL must be dev or release"
}
require(sdkVersion.matches(Regex("(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*)){2}(-SNAPSHOT)?"))) {
    "VERSION_NAME must be X.Y.Z (or X.Y.Z-SNAPSHOT for local builds)"
}
val sdkArtifactId = if (sdkChannel == "dev") "lingora-android-dev" else "lingora-android"
val githubActor = providers.gradleProperty("gpr.user")
    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
val githubToken = providers.gradleProperty("gpr.key")
    .orElse(providers.environmentVariable("GITHUB_TOKEN"))

group = "io.lingora"
version = sdkVersion

android {
    namespace = "io.lingora.android"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.5.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.lingora"
            artifactId = sdkArtifactId
            version = sdkVersion

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set(if (sdkChannel == "dev") "Lingora Android SDK (Development)" else "Lingora Android SDK")
                description.set("Lingora Android SDK for Content Delivery and APK resource fallback.")
                url.set("https://github.com/hzh137940556/lingora-android-sdk")
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/hzh137940556/lingora-android-sdk")
            credentials {
                username = githubActor.orNull
                password = githubToken.orNull
            }
        }
    }
}

// 远端发布必须显式选择渠道和数字版本；普通源码构建仍可使用默认值。
tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        require(providers.gradleProperty("SDK_CHANNEL").isPresent) {
            "Publishing requires an explicit SDK_CHANNEL"
        }
        require(providers.gradleProperty("VERSION_NAME").isPresent && !sdkVersion.endsWith("-SNAPSHOT")) {
            "Publishing requires an explicit X.Y.Z VERSION_NAME"
        }
    }
}
