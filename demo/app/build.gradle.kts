// 构建独立 Demo，消费固定 Maven 坐标；本地替换仅由 Demo settings 配置。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 依赖只声明一次，渠道决定包名；settings 负责校验模式、版本和本地替换。
val sdkMode = providers.gradleProperty("lingoraSdkMode").getOrElse("local")
val sdkArtifact = if (sdkMode == "dev") "lingora-android-dev" else "lingora-android"
val sdkVersion = providers.gradleProperty("lingoraSdkVersion").getOrElse("0.1.0-SNAPSHOT")

val demoApplicationId = providers.gradleProperty("demoApplicationId")
    .getOrElse("com.example.myapplication")

// App 独立选择公开 Delivery 地址，不读取 CLI 配置或密钥。
val lingoraContentDeliveryUrl = providers.gradleProperty("lingoraContentDeliveryUrl")
    .orElse(providers.environmentVariable("LINGORA_CONTENT_DELIVERY_URL"))
    .getOrElse("")
if (lingoraContentDeliveryUrl.isBlank()) {
    logger.warn("Lingora Delivery URL 未配置，Demo 将使用 APK 本地资源。")
}
// 使用 JSON 字符串编码生成合法的 Java BuildConfig 字面量。
val deliveryUrlLiteral = groovy.json.JsonOutput.toJson(lingoraContentDeliveryUrl)

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = demoApplicationId
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "LINGORA_CONTENT_DELIVERY_URL", deliveryUrlLiteral)

        testInstrumentationRunner = "com.example.myapplication.DemoTestRunner"
    }

    sourceSets.getByName("main") {
        res.srcDir("../generated/lingora/res")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation("io.lingora:$sdkArtifact:$sdkVersion")

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
