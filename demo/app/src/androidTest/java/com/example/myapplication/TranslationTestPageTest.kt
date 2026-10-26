package com.example.myapplication

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    // 数组现位于 app namespace 的基础资源文件中。
    @Test
    fun appNamespaceArrayIsAvailableOnTheDemoPage() {
        composeRule.onNodeWithText("en_month").assertExists()
    }
}
