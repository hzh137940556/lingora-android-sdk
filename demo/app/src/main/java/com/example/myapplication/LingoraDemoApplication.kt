// 使用 App 独立配置的公开 Delivery URL 初始化 SDK，语言默认跟随系统。
package com.example.myapplication

import android.app.Application
import io.lingora.android.Lingora
import io.lingora.android.LingoraConfig
import io.lingora.android.LingoraNamespaces

// 在应用启动时初始化单个 SDK 实例，不读取 CLI 文件。
class LingoraDemoApplication : Application() {
    // 未配置远端地址时保留 SDK 的本地资源回退。
    override fun onCreate() {
        super.onCreate()
        Lingora.init(
            context = this,
            config = LingoraConfig(
                contentDeliveryUrl = BuildConfig.LINGORA_CONTENT_DELIVERY_URL,
                appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                defaultLanguage = "en-US",
                path = { languageTag -> "app/$languageTag.json" },
                manifestPath = "manifest.json",
            ),
            namespaces = LingoraNamespaces(
                defaultNamespace = "app",
                additionalNamespaces = setOf("common"),
            ),
        )
    }
}
