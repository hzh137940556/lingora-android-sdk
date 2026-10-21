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

    @Test
    fun localOnlyKeyUsesJapaneseApkFallback() {
        composeRule.runOnUiThread { Lingora.instance.setLocale("ja") }

        composeRule.onNodeWithText("local_notice").performClick()
        composeRule.onNodeWithText("ローカル通知").assertIsDisplayed()
    }

    @Test
    fun arrayFromSeparateXmlIsAvailableOnTheDemoPage() {
        composeRule.onNodeWithText("en_month").assertExists()
    }
}
