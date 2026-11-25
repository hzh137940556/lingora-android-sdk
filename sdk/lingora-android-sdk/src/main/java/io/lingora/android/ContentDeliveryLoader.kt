package io.lingora.android

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
    // 与请求 URL 一致地移除末尾斜杠，隔离不同 Delivery 来源的正文和校验器。
    private val deliveryKey = MessageDigest.getInstance("SHA-256")
        .digest(config.contentDeliveryUrl.trimEnd('/').toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val cacheDirectory = File(
        context.filesDir,
        "lingora/localization-cache/${config.appVersionCode}/$deliveryKey",
    )
    private val metadata = context.getSharedPreferences(
        "lingora-content-delivery-${config.appVersionCode}-$deliveryKey",
        Context.MODE_PRIVATE,
    )

    suspend fun loadManifest(): Result<LoadedResource<ContentDeliveryManifest>> =
        load(config.manifestPath, ContentDeliveryManifest::parse)

    // 按已解析路径加载一个 language/namespace 翻译文档。
    suspend fun loadTranslations(path: String): Result<LoadedResource<TranslationDocument>> =
        load(path, TranslationDocument::parse)

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
        // 落盘失败不影响已解析的网络文档，也不能推进旧缓存的校验器。
        try {
            writeCache(path, response.body)
        } catch (failure: IOException) {
            return LoadedResource(decoded, LoadedSource.NETWORK, "Cache write failed: ${failure.message}")
        }
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
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw IOException("Cannot create Lingora cache directory")
        }
        val atomicFile = AtomicFile(cacheFile(path))
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            // finishWrite 可能仅记录同步失败，提前显式同步以传播 IOException。
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (failure: Exception) {
            atomicFile.failWrite(stream)
            throw failure
        }
        // finishWrite 的重命名失败不会抛异常，确认正文后才能推进 HTTP 校验器。
        if (!atomicFile.openRead().use { it.readBytes() }.contentEquals(bytes)) {
            throw IOException("Lingora cache commit did not preserve the response body")
        }
    }

    // 使用完整路径摘要隔离文档，不读取可能发生碰撞的旧文件名。
    private fun cacheFile(path: String): File = File(
        cacheDirectory,
        "path-sha256-" + MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) },
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

    // 将阻塞请求交给 IO worker；取消立即结束等待并异步断开连接。
    private suspend fun request(path: String, conditional: Boolean): DownloadResponse {
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
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                // disconnect 在部分实现中会等待 IO，不能阻塞取消调用者。
                Dispatchers.IO.dispatch(continuation.context, Runnable { connection.disconnect() })
            }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                if (continuation.isActive) {
                    try {
                        continuation.resume(executeRequest(connection))
                    } catch (failure: Exception) {
                        continuation.resumeWithException(failure)
                    }
                } else {
                    connection.disconnect()
                }
            })
        }
    }

    // 在 worker 内完成 HTTP 读取并始终释放连接；取消后结果由 continuation 丢弃。
    private fun executeRequest(connection: HttpURLConnection): DownloadResponse {
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
