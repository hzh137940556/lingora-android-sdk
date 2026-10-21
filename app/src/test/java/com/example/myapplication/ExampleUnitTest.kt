// 验证 App 构建配置可以传入 SDK，并保留空 URL 与系统语言默认行为。
package com.example.myapplication

import io.lingora.android.LingoraConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

// 不再依赖示例 applicationId 到 URL 的硬编码映射。
class ExampleUnitTest {
    // 生成的公开 URL 可以直接作为 SDK 初始化输入。
    @Test
    fun contentDeliveryUrlUsesAppBuildConfig() {
        val config = LingoraConfig(
            contentDeliveryUrl = BuildConfig.LINGORA_CONTENT_DELIVERY_URL,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        )
        assertEquals(
            BuildConfig.LINGORA_CONTENT_DELIVERY_URL,
            config.contentDeliveryUrl,
        )
    }

    // 无远端地址时仍允许本地回退，当前语言不由 CLI 决定。
    @Test
    fun emptyUrlKeepsFallbackAndSystemLocale() {
        val config = LingoraConfig(contentDeliveryUrl = "", appVersionCode = 1L)
        assertEquals("", config.contentDeliveryUrl)
        assertEquals(Locale.getDefault(), config.initialLocale)
    }
}
