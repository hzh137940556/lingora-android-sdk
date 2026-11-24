package io.lingora.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TranslationDocumentTest {
    // 不支持或不完整的 ICU key 不参与翻译，其他有效 key 保持可用。
    @Test
    fun unsupportedIcuPluralDoesNotBecomePartialTranslation() {
        val unsupported = listOf(
            "{count, plural, =0 {None} other {Items}}",
            "{count, plural, offset:1 one {One} other {Items}}",
            "{count, plural, one {One} other {# items}}",
            "{count, plural, other {{name} items}}",
            "{count, plural, other {Items} trailing}",
            "{count, plural, other {Items} other {Duplicate}}",
            "{count, plural, other {Items}",
        )
        unsupported.forEach { value ->
            val document = TranslationDocument.from(mapOf("bad" to value, "good" to "Hello"))
            assertEquals(null, document.plural("bad"))
            assertEquals(null, document.text("bad"))
            assertEquals("Hello", document.text("good"))
        }
    }

    @Test
    fun parsesTextPluralArrayAndNestedKeys() {
        val document = TranslationDocument.from(
            mapOf(
                "welcome" to "Hello",
                "items" to mapOf("one" to "%1\$d item", "other" to "%1\$d items"),
                "days" to listOf("Mon", "Tue"),
                "account" to mapOf("signIn" to "Sign in"),
            )
        )

        assertEquals("Hello", document.text("welcome"))
        assertEquals("%1\$d items", document.plural("items")?.get("other"))
        assertEquals(listOf("Mon", "Tue"), document.stringArray("days"))
        assertEquals("Sign in", document.text("account.signIn"))
    }

    @Test
    fun resolvesLocaleUsingTolgeeFallbackOrderAndServerCasing() {
        assertEquals(
            "zh-Hans",
            Lingora.resolveLocale(
                requested = Locale.forLanguageTag("zh-Hans-CN"),
                available = listOf("en", "zh-Hans"),
                defaultLanguage = "en",
            ),
        )
        assertEquals(
            "fr-FR",
            Lingora.resolveLocale(
                requested = Locale.forLanguageTag("fr-fr"),
                available = listOf("fr-FR"),
                defaultLanguage = "en",
            ),
        )
        assertEquals(
            "en",
            Lingora.resolveLocale(
                requested = Locale.forLanguageTag("ja-JP"),
                available = listOf("en", "fr"),
                defaultLanguage = "en",
            ),
        )
    }

    @Test
    fun defaultsToAppNamespaceWhileKeepingManifestAtDeliveryRoot() {
        val config = LingoraConfig(
            contentDeliveryUrl = "https://cdn.example.com/dev/v1",
            appVersionCode = 1,
        )

        assertEquals("manifest.json", config.manifestPath)
        assertEquals("app/en-US.json", config.path("en-US"))
    }

    @Test
    fun parsesLegacyIcuPluralAndFormatsPublishedSprintfValues() {
        val document = TranslationDocument.from(
            mapOf(
                "items_count" to (
                    "{count, plural, one {%%1\$d item} other {%%1\$d items}}"
                ),
            ),
        )

        val other = document.plural("items_count")?.get("other")
        assertEquals("%%1\$d items", other)
        assertEquals("3 items", formatSprintf(other!!, Locale.US, arrayOf(3)))
        assertEquals("Hello, Android!", formatSprintf("Hello, %%1\$s!", Locale.US, arrayOf("Android")))
        assertEquals("50%", formatSprintf("50%%", Locale.US, emptyArray()))
    }
}
