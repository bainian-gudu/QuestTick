package com.questtick.net

import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * [HttpTransport] 的 OkHttp 实现。
 *
 * 统一复用受信重定向、协程取消、响应大小和显式重试边界；实例初始化后不再替换底层客户端。
 */
class OkHttpTransport internal constructor(
    okHttpClient: OkHttpClient,
    private val redirectPolicyOverride: RedirectTrustPolicy?,
) : HttpTransport,
    StreamingHttpTransport {
    constructor(okHttpClient: OkHttpClient) : this(okHttpClient, null)

    internal val client: OkHttpClient = okHttpClient.withAutomaticRedirectsAndRetriesDisabled()

    override suspend fun execute(request: HttpRequest): HttpResponse =
        try {
            require(request.config.maxResponseBytes <= HttpRequestConfig.MAX_BUFFERED_RESPONSE_BYTES) {
                "buffered response limit exceeds ${HttpRequestConfig.MAX_BUFFERED_RESPONSE_BYTES} bytes"
            }
            val okHttpRequest = request.toOkHttpRequest()
            val policy =
                redirectPolicyOverride
                    ?: TrustedRedirects.forInitialUrl(request.url)
                    ?: throw UntrustedRedirectException("untrusted initial request URL")
            val requestClient = requestClient(request.config)
            val retryPolicy = request.config.retryMode.toInternalPolicy()
            val snapshot =
                TrustedRedirects.executeSnapshotCancellable(
                    client = requestClient,
                    request = okHttpRequest,
                    policy = policy,
                    maxBodyBytes = request.config.maxResponseBytes,
                    retryPolicy = retryPolicy,
                )
            HttpResponse.bytes(
                code = snapshot.code,
                body = snapshot.body,
                finalUrl = snapshot.finalUrl,
                headers = snapshot.headers.toImmutableMap(),
                charset =
                    snapshot.headers["Content-Type"]
                        ?.toMediaTypeOrNull()
                        ?.charset(Charsets.UTF_8)
                        ?: Charsets.UTF_8,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpTransportException) {
            throw error
        } catch (error: Exception) {
            throw HttpTransportException(HttpFailureMapper.from(error), error)
        }

    override suspend fun <T : Any> executeStreaming(
        request: HttpRequest,
        consume: (HttpResponseStream) -> T,
    ): T =
        try {
            require(request.config.retryMode == HttpRetryMode.NEVER) {
                "streaming transport must be one-shot"
            }
            val okHttpRequest = request.toOkHttpRequest()
            val policy =
                redirectPolicyOverride
                    ?: TrustedRedirects.forInitialUrl(request.url)
                    ?: throw UntrustedRedirectException("untrusted initial request URL")
            val requestClient = requestClient(request.config)
            val retryPolicy = request.config.retryMode.toInternalPolicy()
            TrustedRedirects.executeCancellable(
                client = requestClient,
                request = okHttpRequest,
                policy = policy,
                retryPolicy = retryPolicy,
            ) { response ->
                val contentLength = response.body.contentLength()
                if (contentLength > request.config.maxResponseBytes) {
                    throw ResponseTooLargeException(request.config.maxResponseBytes)
                }
                val stream =
                    HttpResponseStream(
                        code = response.code,
                        finalUrl = response.request.url.toString(),
                        contentLength = contentLength,
                        rawHeaders = response.headers.toImmutableMap(),
                        input = response.body.byteStream(),
                        maxResponseBytes = request.config.maxResponseBytes,
                    )
                try {
                    consume(stream)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: HttpResponseConsumerException) {
                    throw error
                } catch (error: HttpResponseReadException) {
                    throw error
                } catch (error: ResponseTooLargeException) {
                    throw error
                } catch (error: Throwable) {
                    throw HttpResponseConsumerException(error)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpResponseConsumerException) {
            throw error.unwrapConsumerFailure()
        } catch (error: HttpTransportException) {
            throw error
        } catch (error: Exception) {
            throw HttpTransportException(HttpFailureMapper.from(error), error)
        }

    private fun requestClient(config: HttpRequestConfig): OkHttpClient =
        client
            .newBuilder()
            .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(config.callTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()

    private fun HttpRetryMode.toInternalPolicy(): TransportRetryPolicy =
        when (this) {
            HttpRetryMode.NEVER -> TransportRetryPolicy.NEVER
            HttpRetryMode.IDEMPOTENT_READ -> TransportRetryPolicy.IDEMPOTENT_READ
        }

    private fun HttpRequest.toOkHttpRequest(): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        when (method) {
            HttpMethod.GET -> builder.get()
            HttpMethod.HEAD -> builder.head()
            HttpMethod.POST -> builder.post(body.orEmpty().toRequestBody(requireNotNull(contentType).toMediaType()))
        }
        return builder.build()
    }

    internal companion object {
        fun forSameOriginTest(okHttpClient: OkHttpClient): OkHttpTransport = OkHttpTransport(okHttpClient, TrustedRedirects.sameOriginForTest)
    }
}

private fun HttpResponseConsumerException.unwrapConsumerFailure(): Throwable {
    var current: Throwable = this
    val seen = mutableSetOf<Throwable>()
    while (current is HttpResponseConsumerException && seen.add(current)) {
        current = current.cause ?: return current
    }
    return current
}

private fun Headers.toImmutableMap(): Map<String, List<String>> = names().associateWith { name -> values(name).toList() }

private fun OkHttpClient.withAutomaticRedirectsAndRetriesDisabled(): OkHttpClient =
    if (!followRedirects && !followSslRedirects && !retryOnConnectionFailure) {
        this
    } else {
        newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
