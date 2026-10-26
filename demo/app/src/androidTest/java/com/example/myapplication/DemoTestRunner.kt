// 为设备测试提供无网络的 Application，不依赖真实 Delivery 或其文案。
package com.example.myapplication

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import io.lingora.android.Lingora
import io.lingora.android.LingoraConfig
import io.lingora.android.LingoraNamespaces
import java.util.Locale

// 仅在 instrumentation 测试中替换生产 Application。
class DemoTestRunner : AndroidJUnitRunner() {
    // 使用测试进程可加载的 Application 类。
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, OfflineDemoApplication::class.java.name, context)
}

// 固定 SDK 初始语言并禁用网络，测试只读取本次 APK 内置资源。
class OfflineDemoApplication : Application() {
    // 用空 Delivery URL 隔离测试与开发环境服务。
    override fun onCreate() {
        super.onCreate()
        Lingora.init(
            context = this,
            config = LingoraConfig(
                contentDeliveryUrl = "",
                appVersionCode = 0,
                initialLocale = Locale.US,
            ),
            namespaces = LingoraNamespaces(
                defaultNamespace = "app",
                additionalNamespaces = setOf("common"),
            ),
        )
    }
}
