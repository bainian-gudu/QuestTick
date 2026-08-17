package com.questtick.net

import java.util.Collections

/** 纯 JVM 测试替身：记录不可变请求，并按测试提供的挂起处理器返回响应或异常。 */
class FakeHttpTransport(
    private val handler: suspend (HttpRequest) -> HttpResponse,
) : HttpTransport {
    private val recorded = Collections.synchronizedList(mutableListOf<HttpRequest>())

    val requests: List<HttpRequest>
        get() = synchronized(recorded) { recorded.toList() }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        recorded += request
        return handler(request)
    }
}
