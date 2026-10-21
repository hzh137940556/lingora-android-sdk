// 独立声明 Demo App 的插件，不承担 SDK 制包和发布职责。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
