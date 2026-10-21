package io.lingora.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class LingoraRequestsTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val remoteValues = listOf("Remote text", "2 remote items", "Remote Monday|Remote Tuesday")
    private val fallbackValues = listOf("APK text", "2 APK items", "APK Monday|APK Tuesday")

    @Test
    fun concurrentTextPluralAndArrayCollectorsShareSuccessfulDownload(): Unit = runBlocking {
        HttpServer().use { server ->
            val sdk = sdk(server)
            withCollectors(sdk) { values ->
                awaitValues(values, remoteValues)
                sdk.preload()
                assertEquals(1, server.translationRequests.size)
                assertEquals(1, server.requests.count { it.path == "/manifest.json" })
            }
        }
    }

    @Test
    fun persistentFailureIsSharedIncludingNewCollectorsAndRefreshRecovers(): Unit = runBlocking {
        HttpServer().use { server ->
            server.translationResponse = { _, _ -> Reply(503) }
            val sdk = sdk(server)
            withCollectors(sdk) { values ->
                sdk.preload()
                awaitValues(values, fallbackValues)
                assertTrue(sdk.loadState.value is LingoraLoadState.UsingApkFallback)
                assertEquals(2, server.translationRequests.size)
                withCollectors(sdk) { moreValues ->
                    awaitValues(moreValues, fallbackValues)
                    sdk.preload()
                    assertEquals(2, server.translationRequests.size)
                    server.translationResponse = { _, _ -> Reply() }
                    sdk.refresh()
                    awaitValues(values, remoteValues)
                    awaitValues(moreValues, remoteValues)
                    assertEquals(3, server.translationRequests.size)
                    assertTrue(sdk.loadState.value is LingoraLoadState.Ready)
                }
            }
        }
    }

    @Test
    fun transientFailureRetriesOnceWithoutDependingOnAnotherCollector(): Unit = runBlocking {
        HttpServer().use { server ->
            server.translationResponse = { _, number -> if (number == 1) Reply(503) else Reply() }
            val sdk = sdk(server)
            sdk.preload()
            assertEquals("Remote text", sdk.t("audit_text"))
            assertEquals(2, server.translationRequests.size)
            withCollectors(sdk) { values ->
                awaitValues(values, remoteValues)
                assertEquals(2, server.translationRequests.size)
            }
        }
    }

    @Test
    fun missingFileAndInvalidJsonAreNotAutomaticallyRetried(): Unit = runBlocking {
        for (reply in listOf(Reply(404), Reply(body = "invalid json"))) {
            HttpServer().use { server ->
                server.translationResponse = { _, _ -> reply }
                val sdk = sdk(server)
                withCollectors(sdk) { values ->
                    sdk.preload()
                    awaitValues(values, fallbackValues)
                    assertEquals(1, server.translationRequests.size)
                }
            }
        }
    }

    @Test
    fun aLaterCollectorCanRecoverAfterTheFailureCooldown(): Unit = runBlocking {
        HttpServer().use { server ->
            server.translationResponse = { _, _ -> Reply(503) }
            val sdk = sdk(server)
            sdk.preload()
            assertEquals(2, server.translationRequests.size)
            server.translationResponse = { _, _ -> Reply() }
            sdk.preload()
            assertEquals(2, server.translationRequests.size)
            delay(5_100)
            withCollectors(sdk) { values ->
                awaitValues(values, remoteValues)
                assertEquals(3, server.translationRequests.size)
            }
        }
    }

    @Test
    fun cancellingARetryDoesNotCacheFailureOrBlockOtherCallers(): Unit = runBlocking {
        HttpServer().use { server ->
            server.translationResponse = { _, _ -> Reply(503) }
            val sdk = sdk(server)
            val load = launch { sdk.preload() }
            await { server.translationRequests.size == 1 }
            load.cancelAndJoin()
            server.translationResponse = { _, _ -> Reply() }
            withCollectors(sdk) { values ->
                awaitValues(values, remoteValues)
                assertEquals(2, server.translationRequests.size)
            }
        }
    }

    @Test
    fun failureForOneLanguageDoesNotSuppressAnotherLanguage(): Unit = runBlocking {
        HttpServer().use { server ->
            server.translationResponse = { request, _ ->
                if (request.path == "/app/en.json") Reply(503) else Reply()
            }
            val sdk = sdk(server)
            sdk.preload()
            assertEquals(2, server.translationRequests.size)
            sdk.setLocale("ja")
            withCollectors(sdk) { values ->
                awaitValues(values, remoteValues)
                assertEquals(1, server.translationRequests.count { it.path == "/app/ja.json" })
            }
        }
    }

    @Test
    fun aValidDiskCacheAvoidsRetriesWhenTheServerFails(): Unit = runBlocking {
        HttpServer().use { server ->
            val version = System.nanoTime()
            sdk(server, version).preload()
            server.translationResponse = { _, _ -> Reply(503) }
            val cached = sdk(server, version)
            cached.preload()
            assertEquals("Remote text", cached.t("audit_text"))
            assertEquals(LingoraLoadState.Ready("en", LingoraSource.CACHE), cached.loadState.value)
            withCollectors(cached) { values ->
                awaitValues(values, remoteValues)
                assertEquals(2, server.translationRequests.size)
            }
        }
    }

    @Test
    fun notModifiedWithoutCacheKeepsTheNecessaryUnconditionalRequest(): Unit = runBlocking {
        HttpServer().use { server ->
            server.translationResponse = { _, number -> if (number == 1) Reply(304) else Reply() }
            val sdk = sdk(server)
            sdk.preload()
            assertEquals("Remote text", sdk.t("audit_text"))
            assertEquals(2, server.translationRequests.size)
            assertTrue(server.translationRequests.last().headers["if-none-match"] == null)
        }
    }

    @Test
    fun notModifiedWithCacheNeedsNoExtraRequest(): Unit = runBlocking {
        HttpServer().use { server ->
            server.translationResponse = { _, number -> if (number == 1) Reply() else Reply(304) }
            val sdk = sdk(server)
            sdk.preload()
            sdk.refresh()
            assertEquals("Remote text", sdk.t("audit_text"))
            assertEquals(2, server.translationRequests.size)
            assertEquals("\"audit-v1\"", server.translationRequests.last().headers["if-none-match"])
        }
    }

    private fun sdk(server: HttpServer, version: Long = System.nanoTime()): Lingora {
        // A separate instance per test; do not alter the process-wide application singleton.
        val constructor = Lingora::class.java.getDeclaredConstructor(Context::class.java, LingoraConfig::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(context, LingoraConfig(
            contentDeliveryUrl = server.url,
            appVersionCode = version,
            defaultLanguage = "en",
            initialLocale = Locale.ENGLISH,
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000,
        ))
    }

    private suspend fun withCollectors(
        sdk: Lingora,
        check: suspend CoroutineScope.(List<AtomicReference<String>>) -> Unit,
    ) = coroutineScope {
        fun resource(name: String, type: String): Int = context.resources.getIdentifier(name, type, context.packageName)
        val flows: List<Flow<String>> = listOf(
            sdk.tFlow(context, resource("audit_text", "string")),
            sdk.tPluralFlow(context.resources, resource("audit_quantity", "plurals"), 2),
            sdk.tArrayFlow(context.resources, resource("audit_days", "array")).map { it.joinToString("|") },
        )
        val values = List(8) { AtomicReference("") }
        val jobs = values.mapIndexed { index, value -> launch { flows[index % flows.size].collect(value::set) } }
        try {
            await { values.all { it.get().isNotEmpty() } }
            check(values)
        } finally {
            jobs.forEach { it.cancelAndJoin() }
        }
    }

    private suspend fun awaitValues(values: List<AtomicReference<String>>, expected: List<String>) =
        await { values.withIndex().all { (index, value) -> value.get() == expected[index % expected.size] } }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(10)
    }

    private data class Request(val path: String, val headers: Map<String, String>)
    private data class Reply(val status: Int = 200, val body: String = TRANSLATIONS)

    private class HttpServer : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        val translationRequests: List<Request> get() = requests.filter { it.path != "/manifest.json" }
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        @Volatile var translationResponse: (Request, Int) -> Reply = { _, _ -> Reply() }
        private val worker = thread(name = "lingora-http-test") {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: IOException) { break }
                connection.use { client ->
                    client.soTimeout = 2_000
                    try {
                        val reader = client.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.get(1) ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine()?.takeIf { it.isNotEmpty() } ?: break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val request = Request(path, headers)
                        requests.add(request)
                        val reply = if (path == "/manifest.json") {
                            Reply(body = """{"locales":["en","ja"]}""")
                        } else translationResponse(request, translationRequests.size)
                        val body = if (reply.status == 200) reply.body.toByteArray() else byteArrayOf()
                        val header = "HTTP/1.1 ${reply.status} Test\r\nContent-Type: application/json\r\n" +
                            "ETag: \"audit-v1\"\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
                    } catch (_: IOException) {
                        // The cancellation test may disconnect before consuming its response.
                    }
                }
            }
        }
        override fun close() { socket.close(); worker.join(3_000) }
    }

    private companion object {
        const val TRANSLATIONS = """{"audit_text":"Remote text","audit_quantity":{"one":"%d remote item","other":"%d remote items"},"audit_days":["Remote Monday","Remote Tuesday"]}"""
    }
}
