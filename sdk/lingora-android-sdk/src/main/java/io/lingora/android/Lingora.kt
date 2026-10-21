package io.lingora.android

import android.content.Context
import android.content.res.Resources
import android.icu.text.PluralRules
import android.os.SystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.IllegalFormatException
import java.util.Locale

data class LingoraConfig(
    val contentDeliveryUrl: String,
    val appVersionCode: Long,
    val defaultLanguage: String? = null,
    val initialLocale: Locale = Locale.getDefault(),
    val path: (languageTag: String) -> String = { "app/$it.json" },
    val manifestPath: String = "manifest.json",
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 15_000,
) {
    init {
        require(appVersionCode >= 0) { "appVersionCode must not be negative" }
        require(path("en").isNotBlank()) { "path must return a non-blank value" }
        require(manifestPath.isNotBlank()) { "manifestPath must not be blank" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        if (contentDeliveryUrl.isNotBlank()) {
            val uri = URI(contentDeliveryUrl)
            require(
                uri.scheme in setOf("http", "https") &&
                    !uri.host.isNullOrBlank() &&
                    uri.query == null &&
                    uri.fragment == null
            ) {
                "contentDeliveryUrl must be an HTTP(S) URL"
            }
        }
    }
}

enum class LingoraSource { NETWORK, CACHE }

sealed interface LingoraLoadState {
    data object Idle : LingoraLoadState
    data object Loading : LingoraLoadState
    data class Ready(val localeTag: String, val source: LingoraSource) : LingoraLoadState
    data class UsingApkFallback(val reason: String) : LingoraLoadState
}

class Lingora private constructor(
    context: Context,
    private val config: LingoraConfig,
) {
    private data class LoadedLocale(
        val localeTag: String,
        val document: TranslationDocument,
        val source: LingoraSource,
    )

    private data class FailedLoad(
        val localeTag: String,
        val reason: String,
        val retryAfter: Long,
    )

    private val loader = ContentDeliveryLoader(context.applicationContext, config)
    private val loadMutex = Mutex()
    private val _locale = MutableStateFlow(config.initialLocale)
    private val _availableLocaleTags = MutableStateFlow<List<String>>(emptyList())
    private val _loadState = MutableStateFlow<LingoraLoadState>(LingoraLoadState.Idle)
    private val _changeVersion = MutableStateFlow(0L)
    private var manifestAttempted = false
    // Accessed under loadMutex: queued and newly mounted collectors share a failed attempt too.
    private var failedLoad: FailedLoad? = null

    @Volatile
    private var manifestLocales: List<String>? = null

    @Volatile
    private var loadedLocale: LoadedLocale? = null

    val locale = _locale.asStateFlow()
    val availableLocaleTags = _availableLocaleTags.asStateFlow()
    val loadState = _loadState.asStateFlow()

    suspend fun preload() {
        ensureLoaded(_locale.value)
    }

    suspend fun refresh() {
        ensureLoaded(_locale.value, forceRefresh = true)
    }

    fun setLocale(languageTag: String) {
        require(languageTag.isNotBlank()) { "languageTag must not be blank" }
        val locale = Locale.forLanguageTag(languageTag)
        require(locale.language.isNotBlank()) { "Invalid languageTag: $languageTag" }
        _locale.value = locale
    }

    fun t(key: String, vararg formatArgs: Any): String? {
        val value = currentDocument()?.text(key) ?: return null
        return formatSprintf(value, currentFormattingLocale(), formatArgs)
    }

    fun t(context: Context, resourceId: Int, vararg formatArgs: Any): String {
        val key = context.resources.getResourceEntryName(resourceId)
        return t(key, *formatArgs) ?: context.getString(resourceId, *formatArgs)
    }

    fun tPlural(
        resources: Resources,
        resourceId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): String {
        val key = resources.getResourceEntryName(resourceId)
        val args = arrayOf(quantity, *formatArgs)
        val locale = currentFormattingLocale()
        val forms = currentDocument()?.plural(key)
        val category = PluralRules.forLocale(locale).select(quantity.toDouble())
        val remote = forms?.get(category) ?: forms?.get("other")
        return remote?.let { formatSprintf(it, locale, args) }
            ?: resources.getQuantityString(resourceId, quantity, *args)
    }

    fun tArray(resources: Resources, resourceId: Int): List<String> {
        val key = resources.getResourceEntryName(resourceId)
        val fallback = resources.getStringArray(resourceId).toList()
        return currentDocument()?.stringArray(key)?.takeIf { it.size == fallback.size }
            ?: fallback
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun tFlow(context: Context, resourceId: Int, vararg formatArgs: Any): Flow<String> =
        requestedLocaleFlow().transformLatest { requestedLocale ->
            emit(t(context, resourceId, *formatArgs))
            ensureLoaded(requestedLocale, updateLoadState = false)
            emit(t(context, resourceId, *formatArgs))
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun tPluralFlow(
        resources: Resources,
        resourceId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): Flow<String> = requestedLocaleFlow().transformLatest { requestedLocale ->
        emit(tPlural(resources, resourceId, quantity, *formatArgs))
        ensureLoaded(requestedLocale, updateLoadState = false)
        emit(tPlural(resources, resourceId, quantity, *formatArgs))
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun tArrayFlow(resources: Resources, resourceId: Int): Flow<List<String>> =
        requestedLocaleFlow().transformLatest { requestedLocale ->
            emit(tArray(resources, resourceId))
            ensureLoaded(requestedLocale, updateLoadState = false)
            emit(tArray(resources, resourceId))
        }.distinctUntilChanged()

    private fun requestedLocaleFlow(): Flow<Locale> =
        combine(locale, _changeVersion) { requestedLocale, _ -> requestedLocale }

    private suspend fun ensureLoaded(
        requestedLocale: Locale,
        forceRefresh: Boolean = false,
        updateLoadState: Boolean = true,
    ) = loadMutex.withLock {
        if (config.contentDeliveryUrl.isBlank()) {
            if (updateLoadState) {
                _loadState.value = LingoraLoadState.UsingApkFallback(
                    "Content Delivery URL is not configured"
                )
            }
            return@withLock
        }
        if (updateLoadState) _loadState.value = LingoraLoadState.Loading
        loadManifestOnce(forceRefresh)
        val resolvedTag = resolveLocale(
            requested = requestedLocale,
            available = manifestLocales,
            defaultLanguage = config.defaultLanguage,
        )
        if (resolvedTag == null) {
            loadedLocale = null
            if (updateLoadState) {
                _loadState.value = LingoraLoadState.UsingApkFallback(
                    "No published locale matches ${requestedLocale.toLanguageTag()}"
                )
            }
            return@withLock
        }
        if (!forceRefresh) {
            loadedLocale?.takeIf { it.localeTag.equals(resolvedTag, ignoreCase = true) }?.let {
                if (updateLoadState) {
                    _loadState.value = LingoraLoadState.Ready(it.localeTag, it.source)
                }
                return@withLock
            }
            failedLoad?.takeIf {
                it.localeTag.equals(resolvedTag, ignoreCase = true) &&
                    SystemClock.elapsedRealtime() < it.retryAfter
            }?.let {
                if (updateLoadState) {
                    _loadState.value = LingoraLoadState.UsingApkFallback(it.reason)
                }
                return@withLock
            }
        }
        var result = loader.loadTranslations(resolvedTag)
        if ((result.exceptionOrNull() as? ContentDeliveryRequestException)?.retryable == true) {
            // Keep the lock through the bounded retry so collectors cannot each retry the file.
            delay(LOAD_RETRY_DELAY_MILLIS)
            result = loader.loadTranslations(resolvedTag)
        }
        result.fold(
            onSuccess = { result ->
                failedLoad = null
                val source = when (result.source) {
                    LoadedSource.NETWORK -> LingoraSource.NETWORK
                    LoadedSource.CACHE -> LingoraSource.CACHE
                }
                loadedLocale = LoadedLocale(resolvedTag, result.value, source)
                if (updateLoadState) {
                    _loadState.value = LingoraLoadState.Ready(resolvedTag, source)
                }
                _changeVersion.value += 1
            },
            onFailure = { failure ->
                loadedLocale = null
                val reason = failure.message ?: "Translations could not be loaded"
                failedLoad = FailedLoad(
                    resolvedTag, reason, SystemClock.elapsedRealtime() + FAILED_LOAD_COOLDOWN_MILLIS,
                )
                if (updateLoadState) {
                    _loadState.value = LingoraLoadState.UsingApkFallback(reason)
                }
            },
        )
    }

    private suspend fun loadManifestOnce(forceRefresh: Boolean) {
        if (manifestAttempted && !forceRefresh) return
        val result = loader.loadManifest()
        manifestAttempted = true
        result.onSuccess { result ->
            manifestLocales = result.value.locales
            _availableLocaleTags.value = result.value.locales
        }
    }

    private fun currentDocument(): TranslationDocument? {
        val resolved = resolveLocale(_locale.value, manifestLocales, config.defaultLanguage)
        return loadedLocale
            ?.takeIf { resolved != null && it.localeTag.equals(resolved, ignoreCase = true) }
            ?.document
    }

    private fun currentFormattingLocale(): Locale =
        loadedLocale?.localeTag?.let(Locale::forLanguageTag) ?: _locale.value

    companion object {
        private const val LOAD_RETRY_DELAY_MILLIS = 1_000L
        private const val FAILED_LOAD_COOLDOWN_MILLIS = 5_000L

        @Volatile
        private var singleton: Lingora? = null

        val instance: Lingora
            get() = singleton ?: error("Lingora is not initialized")

        fun init(context: Context, config: LingoraConfig): Lingora =
            singleton ?: synchronized(this) {
                singleton ?: Lingora(context, config).also { singleton = it }
            }

        internal fun resolveLocale(
            requested: Locale,
            available: List<String>?,
            defaultLanguage: String?,
        ): String? {
            if (available == null) return requested.toLanguageTag()
            val byTag = available.associateBy(String::lowercase)
            localeFallbackTags(requested).forEach { candidate ->
                byTag[candidate.lowercase()]?.let { return it }
            }
            return defaultLanguage
        }

        private fun localeFallbackTags(locale: Locale): List<String> = buildList {
            fun addTag(vararg parts: String) {
                parts.filter(String::isNotBlank).joinToString("-")
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
            }
            addTag(locale.toLanguageTag())
            addTag(locale.language, locale.script, locale.country, locale.variant)
            addTag(locale.language, locale.script, locale.country)
            addTag(locale.language, locale.script)
            addTag(locale.language, locale.country, locale.variant)
            addTag(locale.language, locale.country)
            addTag(locale.language)
        }.distinct()
    }
}

private val duplicatedIndexedPlaceholder = Regex("""%%(?=\d+[$])""")

internal fun formatSprintf(
    value: String,
    locale: Locale,
    args: Array<out Any>,
): String? = try {
    val compatibleValue = value.replace(duplicatedIndexedPlaceholder, "%")
    String.format(locale, compatibleValue, *args)
} catch (_: IllegalFormatException) {
    null
}
