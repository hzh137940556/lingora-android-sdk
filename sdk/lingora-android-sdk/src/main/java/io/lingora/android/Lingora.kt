// 提供 Lingora Android SDK 的配置、namespace 翻译访问和加载状态。
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

// 配置默认 namespace、附加 namespace 及附加文档路径。
data class LingoraNamespaces(
    val defaultNamespace: String = "app",
    val additionalNamespaces: Set<String> = emptySet(),
    val path: (namespace: String, languageTag: String) -> String =
        { namespace, language -> "$namespace/$language.json" },
) {
    init {
        require(defaultNamespace.isNotBlank()) { "defaultNamespace must not be blank" }
        require(additionalNamespaces.none(String::isBlank)) {
            "additionalNamespaces must not contain blank values"
        }
        require(defaultNamespace !in additionalNamespaces) {
            "additionalNamespaces must not contain defaultNamespace"
        }
        additionalNamespaces.forEach { namespace ->
            require(path(namespace, "en").isNotBlank()) {
                "path must return a non-blank value for namespace $namespace"
            }
        }
    }

    internal val all: List<String> = listOf(defaultNamespace) + additionalNamespaces.sorted()
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
    private val namespaces: LingoraNamespaces,
) {
    private data class DocumentKey(
        val localeTag: String,
        val namespace: String,
    )

    private data class LoadedDocument(
        val localeTag: String,
        val document: TranslationDocument,
        val source: LingoraSource,
    )

    private data class FailedLoad(
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
    // Accessed under loadMutex: collectors share each language/namespace failure cooldown.
    private val failedLoads = mutableMapOf<DocumentKey, FailedLoad>()

    @Volatile
    private var manifestLocales: List<String>? = null

    @Volatile
    private var loadedDocuments: Map<DocumentKey, LoadedDocument> = emptyMap()

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
        return t(key, *formatArgs, namespace = namespaces.defaultNamespace)
    }

    // 从指定 namespace 读取普通字符串；namespace 必须使用具名参数传入。
    fun t(key: String, vararg formatArgs: Any, namespace: String): String? {
        requireConfiguredNamespace(namespace)
        val value = currentDocument(namespace)?.text(key) ?: return null
        return formatSprintf(value, currentFormattingLocale(namespace), formatArgs)
    }

    fun t(context: Context, resourceId: Int, vararg formatArgs: Any): String {
        return t(
            context,
            resourceId,
            *formatArgs,
            namespace = namespaces.defaultNamespace,
        )
    }

    // 从指定 namespace 读取字符串资源，未命中时回退到 APK。
    fun t(
        context: Context,
        resourceId: Int,
        vararg formatArgs: Any,
        namespace: String,
    ): String {
        requireConfiguredNamespace(namespace)
        val key = context.resources.getResourceEntryName(resourceId)
        return t(key, *formatArgs, namespace = namespace)
            ?: context.getString(resourceId, *formatArgs)
    }

    fun tPlural(
        resources: Resources,
        resourceId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): String {
        return tPlural(
            resources,
            resourceId,
            quantity,
            *formatArgs,
            namespace = namespaces.defaultNamespace,
        )
    }

    // 从指定 namespace 读取 plural，未命中时回退到 APK。
    fun tPlural(
        resources: Resources,
        resourceId: Int,
        quantity: Int,
        vararg formatArgs: Any,
        namespace: String,
    ): String {
        requireConfiguredNamespace(namespace)
        val key = resources.getResourceEntryName(resourceId)
        val args = arrayOf(quantity, *formatArgs)
        val locale = currentFormattingLocale(namespace)
        val forms = currentDocument(namespace)?.plural(key)
        val category = PluralRules.forLocale(locale).select(quantity.toDouble())
        val remote = forms?.get(category) ?: forms?.get("other")
        return remote?.let { formatSprintf(it, locale, args) }
            ?: resources.getQuantityString(resourceId, quantity, *args)
    }

    fun tArray(resources: Resources, resourceId: Int): List<String> {
        return tArray(resources, resourceId, namespace = namespaces.defaultNamespace)
    }

    // 从指定 namespace 读取数组，未命中或长度不匹配时回退到 APK。
    fun tArray(resources: Resources, resourceId: Int, namespace: String): List<String> {
        requireConfiguredNamespace(namespace)
        val key = resources.getResourceEntryName(resourceId)
        val fallback = resources.getStringArray(resourceId).toList()
        return currentDocument(namespace)?.stringArray(key)?.takeIf { it.size == fallback.size }
            ?: fallback
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun tFlow(context: Context, resourceId: Int, vararg formatArgs: Any): Flow<String> =
        tFlow(
            context,
            resourceId,
            *formatArgs,
            namespace = namespaces.defaultNamespace,
        )

    // 订阅指定 namespace 的字符串资源更新。
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tFlow(
        context: Context,
        resourceId: Int,
        vararg formatArgs: Any,
        namespace: String,
    ): Flow<String> =
        requestedLocaleFlow().transformLatest { requestedLocale ->
            emit(t(context, resourceId, *formatArgs, namespace = namespace))
            ensureLoaded(requestedLocale, updateLoadState = false)
            emit(t(context, resourceId, *formatArgs, namespace = namespace))
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun tPluralFlow(
        resources: Resources,
        resourceId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): Flow<String> = tPluralFlow(
        resources,
        resourceId,
        quantity,
        *formatArgs,
        namespace = namespaces.defaultNamespace,
    )

    // 订阅指定 namespace 的 plural 资源更新。
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tPluralFlow(
        resources: Resources,
        resourceId: Int,
        quantity: Int,
        vararg formatArgs: Any,
        namespace: String,
    ): Flow<String> = requestedLocaleFlow().transformLatest { requestedLocale ->
        emit(tPlural(resources, resourceId, quantity, *formatArgs, namespace = namespace))
        ensureLoaded(requestedLocale, updateLoadState = false)
        emit(tPlural(resources, resourceId, quantity, *formatArgs, namespace = namespace))
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun tArrayFlow(resources: Resources, resourceId: Int): Flow<List<String>> =
        tArrayFlow(resources, resourceId, namespace = namespaces.defaultNamespace)

    // 订阅指定 namespace 的数组资源更新。
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tArrayFlow(
        resources: Resources,
        resourceId: Int,
        namespace: String,
    ): Flow<List<String>> =
        requestedLocaleFlow().transformLatest { requestedLocale ->
            emit(tArray(resources, resourceId, namespace))
            ensureLoaded(requestedLocale, updateLoadState = false)
            emit(tArray(resources, resourceId, namespace))
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
            if (updateLoadState) {
                _loadState.value = LingoraLoadState.UsingApkFallback(
                    "No published locale matches ${requestedLocale.toLanguageTag()}"
                )
            }
            return@withLock
        }
        val failures = mutableListOf<String>()
        var changed = false
        namespaces.all.forEach { namespace ->
            val key = documentKey(resolvedTag, namespace)
            if (!forceRefresh && loadedDocuments.containsKey(key)) return@forEach
            if (!forceRefresh) {
                failedLoads[key]?.takeIf {
                    SystemClock.elapsedRealtime() < it.retryAfter
                }?.let {
                    failures += "$namespace: ${it.reason}"
                    return@forEach
                }
            }
            val path = translationPath(namespace, resolvedTag)
            var result = loader.loadTranslations(path)
            if ((result.exceptionOrNull() as? ContentDeliveryRequestException)?.retryable == true) {
                // Keep the lock through the bounded retry so collectors cannot retry the same file.
                delay(LOAD_RETRY_DELAY_MILLIS)
                result = loader.loadTranslations(path)
            }
            result.fold(
                onSuccess = { loaded ->
                    failedLoads.remove(key)
                    val source = when (loaded.source) {
                        LoadedSource.NETWORK -> LingoraSource.NETWORK
                        LoadedSource.CACHE -> LingoraSource.CACHE
                    }
                    loadedDocuments = loadedDocuments + (
                        key to LoadedDocument(resolvedTag, loaded.value, source)
                    )
                    changed = true
                },
                onFailure = { failure ->
                    val reason = failure.message ?: "Translations could not be loaded"
                    failedLoads[key] = FailedLoad(
                        reason,
                        SystemClock.elapsedRealtime() + FAILED_LOAD_COOLDOWN_MILLIS,
                    )
                    failures += "$namespace: $reason"
                },
            )
        }
        if (changed) _changeVersion.value += 1
        if (updateLoadState) {
            val current = currentDocuments(resolvedTag)
            _loadState.value = if (current.isNotEmpty()) {
                val source = if (current.any { it.source == LingoraSource.NETWORK }) {
                    LingoraSource.NETWORK
                } else {
                    LingoraSource.CACHE
                }
                LingoraLoadState.Ready(resolvedTag, source)
            } else {
                LingoraLoadState.UsingApkFallback(
                    failures.firstOrNull() ?: "Translations could not be loaded"
                )
            }
        }
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

    // 返回当前语言和 namespace 对应的已加载文档。
    private fun currentDocument(namespace: String): TranslationDocument? {
        val resolved = resolveLocale(_locale.value, manifestLocales, config.defaultLanguage)
        return resolved?.let { loadedDocuments[documentKey(it, namespace)]?.document }
    }

    // 使用对应文档语言格式化参数，未加载时跟随当前 locale。
    private fun currentFormattingLocale(namespace: String): Locale {
        val resolved = resolveLocale(_locale.value, manifestLocales, config.defaultLanguage)
        return resolved?.let { loadedDocuments[documentKey(it, namespace)]?.localeTag }
            ?.let(Locale::forLanguageTag)
            ?: _locale.value
    }

    // 为每个 language/namespace 构造稳定的内存状态键。
    private fun documentKey(localeTag: String, namespace: String): DocumentKey =
        DocumentKey(localeTag.lowercase(Locale.ROOT), namespace)

    // 返回指定语言当前仍有效的全部 namespace 文档。
    private fun currentDocuments(localeTag: String): List<LoadedDocument> =
        namespaces.all.mapNotNull { loadedDocuments[documentKey(localeTag, it)] }

    // 默认 namespace 延续旧 config.path，附加 namespace 使用新路径配置。
    private fun translationPath(namespace: String, localeTag: String): String {
        val path = if (namespace == namespaces.defaultNamespace) {
            config.path(localeTag)
        } else {
            namespaces.path(namespace, localeTag)
        }
        check(path.isNotBlank()) { "Translation path must not be blank" }
        return path
    }

    // 阻止调用未声明 namespace 时静默落入错误文档。
    private fun requireConfiguredNamespace(namespace: String) {
        require(namespace in namespaces.all) { "Namespace is not configured: $namespace" }
    }

    companion object {
        private const val LOAD_RETRY_DELAY_MILLIS = 1_000L
        private const val FAILED_LOAD_COOLDOWN_MILLIS = 5_000L

        @Volatile
        private var singleton: Lingora? = null

        val instance: Lingora
            get() = singleton ?: error("Lingora is not initialized")

        fun init(context: Context, config: LingoraConfig): Lingora =
            init(context, config, LingoraNamespaces())

        // 使用显式 namespace 配置初始化单个 SDK 实例。
        fun init(
            context: Context,
            config: LingoraConfig,
            namespaces: LingoraNamespaces,
        ): Lingora =
            singleton ?: synchronized(this) {
                singleton ?: Lingora(context, config, namespaces).also { singleton = it }
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
