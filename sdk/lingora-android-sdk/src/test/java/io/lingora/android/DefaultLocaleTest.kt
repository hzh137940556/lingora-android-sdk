// 验证默认回退语言的服务端大小写和兼容行为。
package io.lingora.android

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultLocaleTest {
    // 匹配 manifest 时保留发布路径大小写。
    @Test
    fun defaultLanguageUsesPublishedTag() {
        assertEquals("en-US", Lingora.resolveLocale(Locale.JAPANESE, listOf("en-US"), "en-us"))
    }

    // 未发布的默认语言保持原配置，未配置则不回退。
    @Test
    fun absentDefaultKeepsExistingFallback() {
        assertEquals("en-us", Lingora.resolveLocale(Locale.JAPANESE, emptyList(), "en-us"))
        assertNull(Lingora.resolveLocale(Locale.JAPANESE, listOf("en-US"), null))
        assertEquals("ja", Lingora.resolveLocale(Locale.JAPANESE, null, "en-us"))
        assertEquals("ja", Lingora.resolveLocale(Locale.JAPANESE, listOf("ja", "en-US"), "en-us"))
    }
}
