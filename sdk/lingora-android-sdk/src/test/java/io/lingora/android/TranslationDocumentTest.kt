package io.lingora.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TranslationDocumentTest {
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
