package com.questtick.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Root 环境检查边界，业务层可注入 fake，不再直接调用静态检测器或缓存。 */
interface RootEnvironmentChecker {
    fun check(forceRefresh: Boolean = true): RootDetectorV2.RootCheckResult
}

@Singleton
class AndroidRootEnvironmentChecker
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : RootEnvironmentChecker {
        override fun check(forceRefresh: Boolean): RootDetectorV2.RootCheckResult =
            if (forceRefresh) {
                RootCheckCache.forceCheck(context)
            } else {
                RootCheckCache.checkWithCache(context)
            }
    }

/** 将检测证据与业务阻断决策分离，避免风险分数或展示文案隐式改变签到策略。 */
object RootBlockingPolicy {
    enum class Decision {
        ALLOW,
        BLOCK_ROOT_EVIDENCE,
        BLOCK_CHECK_FAILED,
    }

    fun decide(result: RootDetectorV2.RootCheckResult?): Decision =
        when {
            result == null -> Decision.BLOCK_CHECK_FAILED
            result.isRooted -> Decision.BLOCK_ROOT_EVIDENCE
            result.completeness == RootDetectorV2.CheckCompleteness.FAILED -> Decision.BLOCK_CHECK_FAILED
            else -> Decision.ALLOW
        }
}
