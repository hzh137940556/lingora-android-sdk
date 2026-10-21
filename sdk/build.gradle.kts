// 仅声明 SDK Library 构建需要的插件，保持与 Demo 独立。
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
