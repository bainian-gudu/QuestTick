package com.questtick.core

import kotlinx.coroutines.CancellationException

/** 在宽泛异常处理前重新抛出协程取消信号，避免把用户/系统取消转换成普通业务失败。 */
fun Throwable.throwIfCancellation() {
    if (this is CancellationException) throw this
}

/** 与 runCatching 等价，但不会捕获协程取消信号。 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/** 与 Result.recoverCatching 等价，但恢复逻辑被取消时继续向上传播取消信号。 */
inline fun <T> Result<T>.recoverCatchingCancellable(transform: (Throwable) -> T): Result<T> =
    fold(
        onSuccess = { Result.success(it) },
        onFailure = { error -> runCatchingCancellable { transform(error) } },
    )
