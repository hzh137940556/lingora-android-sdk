// 设备测试默认离线；显式传入地址时连接指定的已发布验收语言包。
package com.example.myapplication

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.platform.app.InstrumentationRegistry
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

// 固定初始语言，默认使用 APK，也支持显式指定验收 Delivery。
class OfflineDemoApplication : Application() {
    // 不读取生产 BuildConfig 地址，只有 instrumentation 参数可启用远端。
    override fun onCreate() {
        super.onCreate()
        Lingora.init(
            context = this,
            config = LingoraConfig(
                contentDeliveryUrl = InstrumentationRegistry.getArguments()
                    .getString("lingoraTestDeliveryUrl").orEmpty(),
                defaultLanguage = "en-US",
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
