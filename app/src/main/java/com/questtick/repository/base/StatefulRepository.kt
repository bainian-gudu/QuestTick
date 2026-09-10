package com.questtick.repository.base

import com.questtick.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 带状态管理的 Repository 基类。
 *
 * 除业务快照外显式暴露 Loading / Content / Empty / Error，避免把读取失败解释为“确实为空”。
 */
abstract class StatefulRepository<T>(
    initial: T,
    private val isEmpty: (T) -> Boolean = { false },
    initialLoadEnabled: Boolean = true,
) {
    protected val mutex = Mutex()

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<T> = _state.asStateFlow()

    private val _loadState = MutableStateFlow<RepositoryLoadState>(RepositoryLoadState.InitialLoading)
    val loadState: StateFlow<RepositoryLoadState> = _loadState.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    protected val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        if (initialLoadEnabled) {
            scope.launch {
                try {
                    reload()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError("initial reload failed", e)
                }
            }
        }
    }

    protected fun updateStateAtomically(transform: (T) -> T) {
        _state.update(transform)
        _loaded.value = true
        _loadState.value = stateFor(_state.value)
    }

    protected fun getCurrentState(): T = _state.value

    /** 子类可在 reload 时把持久化数据与当前内存状态合并，默认以持久化数据为准。 */
    protected open fun mergeReloadedData(
        current: T,
        loaded: T,
    ): T = loaded

    protected abstract suspend fun loadFromStore(): T

    protected abstract suspend fun saveToStore(data: T)

    /**
     * 重新加载持久化快照。
     *
     * 异常会在状态流中保留并继续抛给调用者，使启动自动重试和用户手动重试都真正生效。
     */
    open suspend fun reload() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val hadSuccessfulLoad = _loaded.value
                _loadState.value =
                    if (hadSuccessfulLoad) {
                        RepositoryLoadState.Refreshing(hasContent = !isEmpty(_state.value))
                    } else {
                        RepositoryLoadState.InitialLoading
                    }
                try {
                    val data = loadFromStore()
                    _state.update { current -> mergeReloadedData(current, data) }
                    _loaded.value = true
                    _loadState.value = stateFor(_state.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _loadState.value =
                        RepositoryLoadState.Error(
                            hasContent = hadSuccessfulLoad && !isEmpty(_state.value),
                            errorType = e::class.java.simpleName.ifBlank { "StorageReadError" },
                        )
                    logError("reload failed for ${this::class.java.simpleName}", e)
                    throw e
                }
            }
        }

    /** 更新成功落盘后才发布新快照，写入失败不会伪装成内存成功。 */
    protected suspend fun updateAndPersist(transform: (T) -> T) {
        mutex.withLock {
            val newData = transform(_state.value)
            saveToStore(newData)
            _state.value = newData
            _loaded.value = true
            _loadState.value = stateFor(newData)
        }
    }

    private fun stateFor(value: T): RepositoryLoadState = if (isEmpty(value)) RepositoryLoadState.Empty else RepositoryLoadState.Content

    private fun logError(
        message: String,
        error: Throwable,
    ) {
        try {
            AppLog.e("StatefulRepository", message, error)
        } catch (_: RuntimeException) {
            // Android local unit tests use stubbed android.util.Log; logging must never replace the storage error.
        }
    }
}
