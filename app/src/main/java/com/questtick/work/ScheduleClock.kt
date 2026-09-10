package com.questtick.work

import android.os.SystemClock
import com.questtick.net.HttpRequest
import com.questtick.net.HttpRequestConfig
import com.questtick.net.HttpMethod
import com.questtick.net.HttpRetryMode
import com.questtick.net.HttpTransport
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.abs

private const val NETWORK_TIME_URL = "https://www.ntsc.ac.cn/"
private const val AGREEMENT_WINDOW_MILLIS = 5L * 60 * 1000

internal data class ScheduleTimeSources(
    val systemMillis: Long,
    val networkMillis: Long?,
    val monotonicMillis: Long?,
)

internal fun selectScheduleTime(sources: ScheduleTimeSources): Long {
    val candidates =
        buildList {
            add("system" to sources.systemMillis)
            sources.networkMillis?.let { add("network" to it) }
            sources.monotonicMillis?.let { add("monotonic" to it) }
        }
    val agreeingPairs =
        candidates.flatMapIndexed { index, first ->
            candidates.drop(index + 1).mapNotNull { second ->
                if (abs(first.second - second.second) <= AGREEMENT_WINDOW_MILLIS) {
                    listOf(first.second, second.second)
                } else {
                    null
                }
            }
        }
    return when {
        agreeingPairs.isNotEmpty() ->
            agreeingPairs
                .maxBy { pair ->
                    candidates.count { it.second in pair }
                }.average()
                .toLong()
        sources.networkMillis != null -> sources.networkMillis
        else -> sources.systemMillis
    }
}

class ScheduleClock
    @Inject
    constructor(
        private val httpTransport: HttpTransport,
    ) {
        private var systemNow: () -> Long = { System.currentTimeMillis() }
        private var elapsedNow: () -> Long = { SystemClock.elapsedRealtime() }

        internal constructor(
            httpTransport: HttpTransport,
            systemNow: () -> Long,
            elapsedNow: () -> Long,
        ) : this(httpTransport) {
            this.systemNow = systemNow
            this.elapsedNow = elapsedNow
        }

        private data class NetworkSample(
            val wallMillis: Long,
            val elapsedMillis: Long,
        )

        @Volatile private var lastNetworkSample: NetworkSample? = null

        suspend fun nowMillis(): Long {
            val system = systemNow()
            val elapsed = elapsedNow()
            val network = fetchNetworkTime(elapsed)
            val monotonic = lastNetworkSample?.let { it.wallMillis + (elapsed - it.elapsedMillis) }
            return selectScheduleTime(ScheduleTimeSources(system, network, monotonic))
        }

        private suspend fun fetchNetworkTime(receivedElapsed: Long): Long? =
            runCatching {
                val response =
                    httpTransport.execute(
                        HttpRequest(
                            method = HttpMethod.HEAD,
                            url = NETWORK_TIME_URL,
                            config =
                                HttpRequestConfig(
                                    connectTimeoutMillis = 3_000L,
                                    readTimeoutMillis = 3_000L,
                                    callTimeoutMillis = 5_000L,
                                    retryMode = HttpRetryMode.IDEMPOTENT_READ,
                                ),
                        ),
                    )
                if (response.code !in 200..499) return@runCatching null
                val date = response.headerValues("Date").firstOrNull() ?: return@runCatching null
                val millis = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                lastNetworkSample = NetworkSample(millis, receivedElapsed)
                millis
            }.getOrNull()
    }
