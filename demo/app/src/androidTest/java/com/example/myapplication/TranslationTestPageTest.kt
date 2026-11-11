package com.example.myapplication

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.lingora.android.LingoraLoadState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import java.net.HttpURLConnection
import java.net.URL
import io.lingora.android.Lingora
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationTestPageTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tappingTranslationButtonShowsDialog() {
        composeRule.onNodeWithText("welcome").performClick()

        composeRule.onAllNodesWithText("welcome").assertCountEquals(2)
        composeRule.onNodeWithText("Close").assertIsDisplayed()
    }

    // 使用未发布语言验证弹窗读取默认 APK 文案，测试 Application 不发起网络请求。
    @Test
    fun unsupportedLocaleUsesDefaultApkFallback() {
        composeRule.runOnUiThread { Lingora.instance.setLocale("zz") }

        composeRule.onNodeWithText("local_notice").performClick()
        composeRule.onNodeWithText("Local notice").assertIsDisplayed()
    }

    // 离线英语资源必须在同一页面正确展示单数与复数。
    @Test
    fun pluralExamplesUseQuantityForApkFallback() {
        listOf("0 items", "1 item", "2 items", "5 items").forEach { expected ->
            composeRule.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        }
    }

    // 显式验收已发布复数分支及切换后的 UI，避免缺失英文词条时由 APK 掩盖。
    @Test
    fun publishedEnglishAndJapanesePlurals() {
        val deliveryUrl = InstrumentationRegistry.getArguments().getString("lingoraTestDeliveryUrl")
        assumeTrue(!deliveryUrl.isNullOrBlank())
        assertPublishedPluralForms(requireNotNull(deliveryUrl), "en-US", mapOf(
            "one" to "%1\$d item", "other" to "%1\$d items",
        ))
        assertPublishedPluralForms(deliveryUrl, "ja", mapOf("other" to "%1\$d個"))
        for (locale in listOf("en-US", "ja", "en-US")) {
            composeRule.runOnUiThread { Lingora.instance.setLocale(locale) }
            val expected = if (locale == "ja") {
                listOf("0個", "1個", "2個", "5個")
            } else {
                listOf("0 items", "1 item", "2 items", "5 items")
            }
            composeRule.waitUntil(15_000) {
                Lingora.instance.loadState.value is LingoraLoadState.Ready &&
                    composeRule.onAllNodesWithText(expected.first()).fetchSemanticsNodes().isNotEmpty()
            }
            expected.forEach { value ->
                composeRule.onNodeWithText(value).performScrollTo().assertIsDisplayed()
            }
        }
    }

    // 独立读取公开语言包并检查必需分支；HTTP 失败或缺少词条不能被 APK 回退掩盖。
    private fun assertPublishedPluralForms(
        deliveryUrl: String,
        locale: String,
        expected: Map<String, String>,
    ) {
        val connection = URL("${deliveryUrl.trimEnd('/')}/app/$locale.json")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            assertEquals("Published $locale document", 200, connection.responseCode)
            val document = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val forms = document.getJSONObject("items_count")
            expected.forEach { (category, template) ->
                assertEquals("$locale items_count.$category", template, forms.getString(category))
            }
        } finally {
            connection.disconnect()
        }
    }

    // 数组现位于 app namespace 的基础资源文件中。
    @Test
    fun appNamespaceArrayIsAvailableOnTheDemoPage() {
        composeRule.onNodeWithText("en_month").assertExists()
    }
}
