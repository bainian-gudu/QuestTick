package com.questtick.net

/** 统一 API 调用结果，供后续网络层逐步替换分散 try-catch。 */
sealed interface ApiResult<out T> {
    data class Success<T>(
        val data: T,
    ) : ApiResult<T>

    data class Error(
        val code: Int? = null,
        val message: String,
        val detail: String = "",
    ) : ApiResult<Nothing>

    data class NetworkError(
        val message: String,
        val detail: String = "",
        val failure: HttpFailure,
    ) : ApiResult<Nothing>

    data class RiskControlError(
        val message: String,
        val detail: String = "",
    ) : ApiResult<Nothing>

    val isSuccess: Boolean get() = this is Success<*>

    fun getOrNull(): T? =
        when (this) {
            is Success -> data
            else -> null
        }
}
