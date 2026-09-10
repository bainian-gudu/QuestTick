package com.questtick.repository.base

/** Repository 持久化快照的可观察加载状态。 */
sealed interface RepositoryLoadState {
    /** 首次读取尚未结束，不能把默认值解释为真实空数据。 */
    data object InitialLoading : RepositoryLoadState

    /** 已有可展示快照，正在后台刷新。 */
    data class Refreshing(
        val hasContent: Boolean,
    ) : RepositoryLoadState

    /** 持久化读取成功且存在内容。 */
    data object Content : RepositoryLoadState

    /** 持久化读取成功且确认没有内容。 */
    data object Empty : RepositoryLoadState

    /** 持久化读取失败；hasContent 表示仍可继续展示上一次成功快照。 */
    data class Error(
        val hasContent: Boolean,
        val errorType: String,
    ) : RepositoryLoadState
}

fun RepositoryLoadState.isInitialLoading(): Boolean = this is RepositoryLoadState.InitialLoading

fun RepositoryLoadState.isError(): Boolean = this is RepositoryLoadState.Error
