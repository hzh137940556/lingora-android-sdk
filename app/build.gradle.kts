// 配置 Demo 构建，并在 dependencies 中统一选择 SDK 的本地或 Maven 依赖。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main") {
        res.srcDir("../generated/lingora/res")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    // SDK 引入方式三选一；切换时注释当前行，再启用目标行。
    implementation(project(":lingora-android-sdk"))
    // implementation("io.lingora:lingora-android:0.1.0") // 远端正式包
    // implementation("io.lingora:lingora-android-dev:0.1.1") // 远端测试包

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
