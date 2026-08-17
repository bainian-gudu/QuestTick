package com.questtick.net

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** 单次请求使用的重定向信任边界；初始地址和每个候选地址都必须通过 [allows]。 */
internal fun interface RedirectTrustPolicy {
    fun allows(
        initialUrl: HttpUrl,
        candidateUrl: HttpUrl,
    ): Boolean
}

internal class UntrustedRedirectException(
    message: String,
) : IOException(message)

/**
 * 手动跟随受信重定向。
 *
 * OkHttp 自动重定向必须保持关闭；这里在发送初始请求前和发送每一跳前完成用途级校验，并限制跳数与循环。
 */
internal object TrustedRedirects {
    const val MAX_REDIRECTS = 5

    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private val redirectableMethods = setOf("GET", "HEAD")
    private val crossOriginSafeHeaders =
        setOf(
            "Accept",
            "Accept-Encoding",
            "Accept-Language",
            "Cache-Control",
            "If-None-Match",
            "Pragma",
            "Range",
            "User-Agent",
        )

    fun forInitialUrl(rawUrl: String): RedirectTrustPolicy? =
        when {
            TrustedUrlPolicy.isActivityResourceUrl(rawUrl) -> activityResource
            TrustedUrlPolicy.isRewardIconUrl(rawUrl) -> rewardIcon
            TrustedUrlPolicy.isUpdateMetadataUrl(rawUrl) -> updateMetadata
            TrustedUrlPolicy.isUpdateAssetUrl(rawUrl) -> updateAsset
            TrustedUrlPolicy.isBusinessRequestUrl(rawUrl) -> sameOriginHttps
            TrustedUrlPolicy.isTimeSourceUrl(rawUrl) -> timeSource
            else -> null
        }

    val activityResource = RedirectTrustPolicy { _, candidate ->
        TrustedUrlPolicy.isActivityResourceUrl(candidate.toString())
    }

    val rewardIcon = RedirectTrustPolicy { _, candidate ->
        TrustedUrlPolicy.isRewardIconUrl(candidate.toString())
    }

    val updateMetadata = RedirectTrustPolicy { initial, candidate ->
        TrustedUrlPolicy.isUpdateMetadataRedirect(initial.toString(), candidate.toString())
    }

    val updateAsset = RedirectTrustPolicy { initial, candidate ->
        TrustedUrlPolicy.isUpdateAssetRedirect(initial.toString(), candidate.toString())
    }

    val sameOriginHttps = RedirectTrustPolicy { initial, candidate ->
        TrustedUrlPolicy.isBusinessRequestUrl(initial.toString()) &&
            TrustedUrlPolicy.isBusinessRequestUrl(candidate.toString()) &&
            TrustedUrlPolicy.isSameOriginHttps(initial.toString(), candidate.toString())
    }

    val timeSource = RedirectTrustPolicy { initial, candidate ->
        TrustedUrlPolicy.isTimeSourceUrl(initial.toString()) &&
            TrustedUrlPolicy.isTimeSourceUrl(candidate.toString()) &&
            TrustedUrlPolicy.isSameOriginHttps(initial.toString(), candidate.toString())
    }

    /** 只供 JVM MockWebServer 测试使用；仍限制为完全相同的 Scheme、主机和端口。 */
    internal val sameOriginForTest = RedirectTrustPolicy { initial, candidate ->
        initial.host.isLoopbackHost() && candidate.host.isLoopbackHost() &&
            initial.scheme == candidate.scheme &&
            initial.host == candidate.host &&
            initial.port == candidate.port &&
            initial.username.isEmpty() && initial.password.isEmpty() &&
            candidate.username.isEmpty() && candidate.password.isEmpty() &&
            initial.fragment == null && candidate.fragment == null
    }

    fun execute(
        client: OkHttpClient,
        request: Request,
        policy: RedirectTrustPolicy,
        retryPolicy: TransportRetryPolicy = TransportRetryPolicy.NEVER,
    ): Response {
        retryPolicy.requireCompatible(request)
        val safeClient = client.withAutomaticRedirectsAndRetriesDisabled()
        val state = RedirectState(request, policy)
        while (true) {
            val response = executeCall(safeClient, state.currentRequest, retryPolicy)
            if (!isRedirect(response)) {
                requireAllowed(policy, state.initialUrl, response.request.url, "final")
                return response.withClassifiedResponseBody()
            }
            response.use(state::advance)
        }
    }

    fun <T> executeMapped(
        client: OkHttpClient,
        request: Request,
        policy: RedirectTrustPolicy,
        retryPolicy: TransportRetryPolicy = TransportRetryPolicy.NEVER,
        transform: (Response) -> T,
    ): T {
        retryPolicy.requireCompatible(request)
        val safeClient = client.withAutomaticRedirectsAndRetriesDisabled()
        val state = RedirectState(request, policy)
        redirectLoop@ while (true) {
            var attempts = 0
            while (true) {
                attempts++
                val (attemptClient, attemptRequest) = trackedAttempt(safeClient, state.currentRequest)
                try {
                    val response = attemptClient.newCall(attemptRequest).execute()
                    try {
                        if (isRedirect(response)) {
                            state.advance(response)
                            continue@redirectLoop
                        }
                        requireAllowed(policy, state.initialUrl, response.request.url, "final")
                        return transform(response)
                    } finally {
                        response.close()
                    }
                } catch (error: IOException) {
                    val failure = TransportFailures.classify(error, attemptRequest)
                    if (!TransportFailures.shouldRetry(failure, state.currentRequest, retryPolicy, attempts)) {
                        throw failure
                    }
                }
            }
        }
    }

    suspend fun <T : Any> executeCancellable(
        client: OkHttpClient,
        request: Request,
        policy: RedirectTrustPolicy,
        retryPolicy: TransportRetryPolicy = TransportRetryPolicy.NEVER,
        transform: (Response) -> T,
    ): T {
        retryPolicy.requireCompatible(request)
        val safeClient = client.withAutomaticRedirectsAndRetriesDisabled()
        val state = RedirectState(request, policy)
        while (true) {
            val result =
                executeCallCancellable(safeClient, state.currentRequest, retryPolicy) { response ->
                    if (isRedirect(response)) {
                        state.advance(response)
                        null
                    } else {
                        requireAllowed(policy, state.initialUrl, response.request.url, "final")
                        transform(response)
                    }
                }
            if (result != null) return result
        }
    }

    suspend fun executeSnapshotCancellable(
        client: OkHttpClient,
        request: Request,
        policy: RedirectTrustPolicy,
        maxBodyBytes: Int = 0,
        retryPolicy: TransportRetryPolicy = TransportRetryPolicy.NEVER,
    ): OkHttpResponseSnapshot {
        retryPolicy.requireCompatible(request)
        val safeClient = client.withAutomaticRedirectsAndRetriesDisabled()
        val state = RedirectState(request, policy)
        while (true) {
            val snapshot =
                executeCallCancellable(safeClient, state.currentRequest, retryPolicy) { response ->
                    if (isRedirect(response)) {
                        state.advance(response)
                        null
                    } else {
                        requireAllowed(policy, state.initialUrl, response.request.url, "final")
                        response.readSnapshot(maxBodyBytes)
                    }
                }
            if (snapshot != null) return snapshot
        }
    }

    private fun executeCall(
        client: OkHttpClient,
        request: Request,
        retryPolicy: TransportRetryPolicy,
    ): Response {
        var attempts = 0
        while (true) {
            attempts++
            val (attemptClient, attemptRequest) = trackedAttempt(client, request)
            try {
                return attemptClient.newCall(attemptRequest).execute()
            } catch (error: IOException) {
                val failure = TransportFailures.classify(error, attemptRequest)
                if (!TransportFailures.shouldRetry(failure, request, retryPolicy, attempts)) throw failure
            }
        }
    }

    private suspend fun <T> executeCallCancellable(
        client: OkHttpClient,
        request: Request,
        retryPolicy: TransportRetryPolicy,
        transform: (Response) -> T,
    ): T {
        var attempts = 0
        while (true) {
            attempts++
            val (attemptClient, attemptRequest) = trackedAttempt(client, request)
            try {
                return attemptClient.newCall(attemptRequest).mapResponseCancellable(transform)
            } catch (error: IOException) {
                val failure = TransportFailures.classify(error, attemptRequest)
                if (!TransportFailures.shouldRetry(failure, request, retryPolicy, attempts)) throw failure
            }
        }
    }

    private fun trackedAttempt(
        client: OkHttpClient,
        request: Request,
    ): Pair<OkHttpClient, Request> {
        val tracker = RequestTransmissionTracker()
        val attemptRequest =
            request.newBuilder()
                .tag(RequestTransmissionTracker::class.java, tracker)
                .build()
        val attemptClient = client.newBuilder().eventListener(tracker).build()
        return attemptClient to attemptRequest
    }

    private fun isRedirect(response: Response): Boolean = response.code in redirectCodes

    private class RedirectState(
        initialRequest: Request,
        private val policy: RedirectTrustPolicy,
    ) {
        val initialUrl = initialRequest.url
        var currentRequest: Request = initialRequest
            private set
        private val visited = linkedSetOf(initialUrl.toString())
        private var redirects = 0

        init {
            requireAllowed(policy, initialUrl, initialUrl, "initial")
        }

        fun advance(response: Response) {
            if (currentRequest.method !in redirectableMethods) {
                throw UntrustedRedirectException("redirect is not allowed for ${currentRequest.method}")
            }
            if (redirects >= MAX_REDIRECTS) {
                throw UntrustedRedirectException("redirect limit exceeded: $MAX_REDIRECTS")
            }
            val nextUrl = resolveLocation(response)
            requireAllowed(policy, initialUrl, nextUrl, "hop ${redirects + 1}")
            if (!visited.add(nextUrl.toString())) {
                throw UntrustedRedirectException("redirect loop detected")
            }
            currentRequest = redirectedRequest(currentRequest, nextUrl)
            redirects++
        }
    }

    internal fun resolveLocation(response: Response): HttpUrl {
        val locations = response.headers.values("Location")
        if (locations.size != 1) {
            throw UntrustedRedirectException("redirect response must have exactly one Location")
        }
        val location = locations.single()
        if (
            location.isBlank() || location != location.trim() || '\\' in location ||
            location.any { it.isISOControl() || it.isWhitespace() }
        ) {
            throw UntrustedRedirectException("invalid redirect Location")
        }
        return response.request.url.resolve(location)
            ?: throw UntrustedRedirectException("invalid redirect Location")
    }

    internal fun redirectedRequest(
        request: Request,
        nextUrl: HttpUrl,
    ): Request {
        val builder = request.newBuilder().url(nextUrl)
        if (!request.url.sameOrigin(nextUrl)) {
            request.headers.names()
                .filterNot { name -> crossOriginSafeHeaders.any { safe -> name.equals(safe, ignoreCase = true) } }
                .forEach(builder::removeHeader)
        }
        return builder.build()
    }

    internal fun requireAllowed(
        policy: RedirectTrustPolicy,
        initialUrl: HttpUrl,
        candidateUrl: HttpUrl,
        stage: String,
    ) {
        if (!policy.allows(initialUrl, candidateUrl)) {
            throw UntrustedRedirectException("untrusted redirect $stage target")
        }
    }

    private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private fun Response.withClassifiedResponseBody(): Response {
        val originalBody = body
        val classifiedSource =
            object : ForwardingSource(originalBody.source()) {
                override fun read(
                    sink: okio.Buffer,
                    byteCount: Long,
                ): Long =
                    try {
                        super.read(sink, byteCount)
                    } catch (error: IOException) {
                        throw TransportFailures.classify(error, request)
                    }
            }.buffer()
        return newBuilder()
            .body(classifiedSource.asResponseBody(originalBody.contentType(), originalBody.contentLength()))
            .build()
    }

    private fun String.isLoopbackHost(): Boolean =
        this == "localhost" || this == "127.0.0.1" || this == "::1"

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
}
