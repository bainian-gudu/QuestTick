package com.questtick.net

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.nio.charset.Charset

/** 与具体 HTTP 实现无关的请求方法。 */
enum class HttpMethod {
    GET,
    HEAD,
    POST,
}

/** 调用方可声明的传输重试权限；默认和所有写请求都必须使用 [NEVER]。 */
enum class HttpRetryMode {
    NEVER,
    IDEMPOTENT_READ,
}

/**
 * 单次请求的传输配置。
 *
 * 响应上限、总调用超时和重试权限必须由调用点显式携带，Fake 与真实实现使用同一份配置。
 */
data class HttpRequestConfig(
    val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
    val writeTimeoutMillis: Long = DEFAULT_WRITE_TIMEOUT_MILLIS,
    val callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
    val retryMode: HttpRetryMode = HttpRetryMode.NEVER,
) {
    init {
        require(maxResponseBytes in 1..MAX_RESPONSE_BYTES) {
            "maxResponseBytes must be in 1..$MAX_RESPONSE_BYTES"
        }
        require(connectTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "invalid connect timeout" }
        require(readTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "invalid read timeout" }
        require(writeTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "invalid write timeout" }
        require(callTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "invalid call timeout" }
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_READ_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_WRITE_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_CALL_TIMEOUT_MILLIS = 25_000L
        const val MAX_BUFFERED_RESPONSE_BYTES = 16 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 512 * 1024 * 1024
        const val MAX_TIMEOUT_MILLIS = 120_000L
    }
}

/**
 * 不在 [toString] 中输出 URL、请求头或请求体，避免 Cookie、Token 与票据进入诊断日志。
 */
class HttpRequest(
    val method: HttpMethod,
    val url: String,
    headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
    val config: HttpRequestConfig = HttpRequestConfig(),
) {
    val headers: Map<String, String> = headers.toMap()

    init {
        require(url.isNotBlank()) { "request URL must not be blank" }
        when (method) {
            HttpMethod.GET,
            HttpMethod.HEAD,
            -> require(body == null && contentType == null) { "$method must not have a request body" }
            HttpMethod.POST -> {
                require(body != null) { "POST requires a request body" }
                require(!contentType.isNullOrBlank()) { "POST requires a content type" }
            }
        }
        if (config.retryMode == HttpRetryMode.IDEMPOTENT_READ) {
            require(method == HttpMethod.GET || method == HttpMethod.HEAD) {
                "IDEMPOTENT_READ only supports GET or HEAD"
            }
        }
    }

    override fun toString(): String = "HttpRequest(method=$method, sensitiveFields=redacted)"
}

/** 已完整读取并关闭底层响应后的不可变快照。 */
class HttpResponse internal constructor(
    val code: Int,
    private val rawBody: ByteArray,
    val finalUrl: String,
    private val rawHeaders: Map<String, List<String>>,
    private val bodyCharset: Charset,
) {
    /** 文本接口按响应声明字符集解码，缺失或非法时由实现回退 UTF-8；二进制调用方使用 [bodyBytes]。 */
    val body: String by lazy { rawBody.toString(bodyCharset) }

    /** 返回副本，避免调用方修改传输层持有的响应快照。 */
    fun bodyBytes(): ByteArray = rawBody.copyOf()

    fun headerValues(name: String): List<String> =
        rawHeaders.entries
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            .orEmpty()

    fun json(): JSONObject =
        try {
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } catch (_: Exception) {
            JSONObject()
        }

    override fun toString(): String = "HttpResponse(code=$code, bodyBytes=${rawBody.size}, finalUrl=redacted, headers=redacted)"

    companion object {
        /** 供 Fake 与纯单元测试创建响应，不依赖 OkHttp 类型。 */
        fun text(
            code: Int,
            body: String,
            finalUrl: String = "",
            headers: Map<String, List<String>> = emptyMap(),
        ): HttpResponse = HttpResponse(code, body.toByteArray(Charsets.UTF_8), finalUrl, headers.immutableCopy(), Charsets.UTF_8)

        fun bytes(
            code: Int,
            body: ByteArray,
            finalUrl: String = "",
            headers: Map<String, List<String>> = emptyMap(),
            charset: Charset = Charsets.UTF_8,
        ): HttpResponse = HttpResponse(code, body.copyOf(), finalUrl, headers.immutableCopy(), charset)
    }
}

/** 业务和 API 客户端只依赖此边界；测试可直接注入 lambda Fake。 */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

suspend fun HttpTransport.get(
    url: String,
    headers: Map<String, String> = emptyMap(),
    config: HttpRequestConfig = HttpRequestConfig(),
): HttpResponse =
    execute(
        HttpRequest(
            method = HttpMethod.GET,
            url = url,
            headers = headers,
            config = config.copy(retryMode = HttpRetryMode.NEVER),
        ),
    )

/** 仅供调用方已确认无副作用的 GET 使用；传输失败时最多补发一次。 */
suspend fun HttpTransport.getIdempotent(
    url: String,
    headers: Map<String, String> = emptyMap(),
    config: HttpRequestConfig = HttpRequestConfig(),
): HttpResponse =
    execute(
        HttpRequest(
            method = HttpMethod.GET,
            url = url,
            headers = headers,
            config = config.copy(retryMode = HttpRetryMode.IDEMPOTENT_READ),
        ),
    )

suspend fun HttpTransport.postJson(
    url: String,
    headers: Map<String, String> = emptyMap(),
    json: JSONObject,
    config: HttpRequestConfig = HttpRequestConfig(),
): HttpResponse =
    execute(
        HttpRequest(
            method = HttpMethod.POST,
            url = url,
            headers = headers,
            body = json.toString(),
            contentType = JSON_CONTENT_TYPE,
            config = config.copy(retryMode = HttpRetryMode.NEVER),
        ),
    )

suspend fun HttpTransport.getResult(
    url: String,
    headers: Map<String, String> = emptyMap(),
    config: HttpRequestConfig = HttpRequestConfig(),
): ApiResult<HttpResponse> =
    executeResult(
        HttpRequest(
            method = HttpMethod.GET,
            url = url,
            headers = headers,
            config = config.copy(retryMode = HttpRetryMode.NEVER),
        ),
    )

suspend fun HttpTransport.postJsonResult(
    url: String,
    headers: Map<String, String> = emptyMap(),
    json: JSONObject,
    config: HttpRequestConfig = HttpRequestConfig(),
): ApiResult<HttpResponse> =
    executeResult(
        HttpRequest(
            method = HttpMethod.POST,
            url = url,
            headers = headers,
            body = json.toString(),
            contentType = JSON_CONTENT_TYPE,
            config = config.copy(retryMode = HttpRetryMode.NEVER),
        ),
    )

suspend fun HttpTransport.executeResult(request: HttpRequest): ApiResult<HttpResponse> =
    try {
        val response = execute(request)
        if (response.code in 200..299) {
            ApiResult.Success(response)
        } else {
            ApiResult.Error(
                code = response.code,
                message = "HTTP ${response.code}",
                detail = "http:${response.code}",
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = HttpFailureMapper.from(error)
        ApiResult.NetworkError(
            message = failure.errorCode,
            detail = failure.errorCode,
            failure = failure,
        )
    }

private fun Map<String, List<String>>.immutableCopy(): Map<String, List<String>> = entries.associate { (name, values) -> name to values.toList() }

private const val JSON_CONTENT_TYPE = "application/json;charset=utf-8"
