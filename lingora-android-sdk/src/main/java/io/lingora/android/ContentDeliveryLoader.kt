package io.lingora.android

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal enum class LoadedSource { NETWORK, CACHE }

internal data class LoadedResource<T>(
    val value: T,
    val source: LoadedSource,
    val warning: String? = null,
)

internal class ContentDeliveryRequestException(
    message: String,
    val retryable: Boolean,
) : IOException(message)

internal class ContentDeliveryLoader(
    context: Context,
    private val config: LingoraConfig,
) {
    private val cacheDirectory = File(
        context.filesDir,
        "lingora/localization-cache/${config.appVersionCode}",
    )
    private val metadata = context.getSharedPreferences(
        "lingora-content-delivery-${config.appVersionCode}",
        Context.MODE_PRIVATE,
    )

    suspend fun loadManifest(): Result<LoadedResource<ContentDeliveryManifest>> =
        load(config.manifestPath, ContentDeliveryManifest::parse)

    suspend fun loadTranslations(
        localeTag: String,
    ): Result<LoadedResource<TranslationDocument>> =
        load(config.path(localeTag), TranslationDocument::parse)

    private suspend fun <T> load(
        path: String,
        decode: (ByteArray) -> T,
    ): Result<LoadedResource<T>> = withContext(Dispatchers.IO) {
        runCatching {
            val cached = readCached(path, decode)
            when (val response = request(path, conditional = true)) {
                is DownloadResponse.Success -> decodeFresh(path, response, decode, cached)
                DownloadResponse.NotModified -> cached
                    ?: when (val retry = request(path, conditional = false)) {
                        is DownloadResponse.Success -> decodeFresh(path, retry, decode, null)
                        else -> error("Content Delivery returned 304 without a valid local cache")
                    }
                is DownloadResponse.Failure -> cached?.copy(warning = response.reason)
                    ?: throw ContentDeliveryRequestException(response.reason, response.retryable)
            }
        }
    }

    private fun <T> decodeFresh(
        path: String,
        response: DownloadResponse.Success,
        decode: (ByteArray) -> T,
        cached: LoadedResource<T>?,
    ): LoadedResource<T> {
        val decoded = runCatching { decode(response.body) }.getOrElse { failure ->
            return cached?.copy(warning = "Invalid Content Delivery JSON: ${failure.message}")
                ?: throw failure
        }
        writeCache(path, response.body)
        saveValidators(path, response.etag, response.lastModified)
        return LoadedResource(decoded, LoadedSource.NETWORK)
    }

    private fun <T> readCached(path: String, decode: (ByteArray) -> T): LoadedResource<T>? {
        val file = cacheFile(path)
        if (!file.isFile) return null
        return runCatching { LoadedResource(decode(file.readBytes()), LoadedSource.CACHE) }
            .getOrNull()
    }

    private fun writeCache(path: String, bytes: ByteArray) {
        check(cacheDirectory.exists() || cacheDirectory.mkdirs()) {
            "Cannot create Lingora cache directory"
        }
        val atomicFile = AtomicFile(cacheFile(path))
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (failure: Exception) {
            atomicFile.failWrite(stream)
            throw failure
        }
    }

    private fun cacheFile(path: String): File = File(
        cacheDirectory,
        path.replace(Regex("[^A-Za-z0-9._-]"), "_"),
    )

    private fun saveValidators(path: String, etag: String?, lastModified: String?) {
        metadata.edit().apply {
            if (etag == null) remove("$path.etag") else putString("$path.etag", etag)
            if (lastModified == null) {
                remove("$path.last-modified")
            } else {
                putString("$path.last-modified", lastModified)
            }
        }.apply()
    }

    private fun request(path: String, conditional: Boolean): DownloadResponse {
        if (config.contentDeliveryUrl.isBlank()) {
            return DownloadResponse.Failure("Content Delivery URL is not configured")
        }
        val url = "${config.contentDeliveryUrl.trimEnd('/')}/$path"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = config.connectTimeoutMillis
            readTimeout = config.readTimeoutMillis
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("sdkType", "Lingora Android")
            setRequestProperty("sdkVersion", BuildConfig.SDK_VERSION)
            if (conditional) {
                metadata.getString("$path.etag", null)?.let {
                    setRequestProperty("If-None-Match", it)
                }
                metadata.getString("$path.last-modified", null)?.let {
                    setRequestProperty("If-Modified-Since", it)
                }
            }
        }
        return try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> DownloadResponse.Success(
                    body = connection.inputStream.use(::readLimited),
                    etag = connection.getHeaderField("ETag"),
                    lastModified = connection.getHeaderField("Last-Modified"),
                )
                HttpURLConnection.HTTP_NOT_MODIFIED -> DownloadResponse.NotModified
                else -> DownloadResponse.Failure(
                    "Content Delivery returned HTTP $status",
                    retryable = status in setOf(408, 500, 502, 503, 504),
                )
            }
        } catch (failure: IOException) {
            DownloadResponse.Failure(
                failure.message ?: "Content Delivery request failed",
                retryable = (failure as? ContentDeliveryRequestException)?.retryable ?: true,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) {
                throw ContentDeliveryRequestException(
                    "Content Delivery response exceeds $MAX_RESPONSE_BYTES bytes", retryable = false,
                )
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private sealed interface DownloadResponse {
        data class Success(
            val body: ByteArray,
            val etag: String?,
            val lastModified: String?,
        ) : DownloadResponse

        data object NotModified : DownloadResponse
        data class Failure(val reason: String, val retryable: Boolean = false) : DownloadResponse
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024
    }
}
