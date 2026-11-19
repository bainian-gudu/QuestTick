package com.questtick.log

/**
 * 运行日志里带语义的固定文案。
 *
 * 写入方（runner / worker / viewmodel）与解析方（日志页分组）必须引用同一份常量：
 * 此前两处各写一份中文字符串，任何措辞调整都会静默改变运行分组与「手动 / 自动」标签，
 * 而测试只能锁住「当前文案恰好没变」。
 */
object LogMarkers {
    /** 一次运行的起点；详情里带 trigger=<枚举>，供日志页读取。 */
    const val RUN_START = "========== 开始执行签到 =========="

    /** 识别运行起点行的关键字（兼容历史日志）。 */
    const val RUN_START_KEYWORD = "开始执行签到"

    /** 一次运行的终点前缀，实际写入时会拼上耗时。 */
    const val RUN_END_PREFIX = "========== 签到结束"

    /** 识别运行终点行的关键字。 */
    const val RUN_END_KEYWORD = "签到结束"

    /** 手动运行在取锁前写入的提交标记。 */
    const val MANUAL_SUBMITTED = "手动签到已提交执行"

    /** 定时运行在取锁前写入的提交标记。 */
    const val SCHEDULED_SUBMITTED = "定时签到已提交执行"

    /** 识别提交标记的关键字（两种触发来源共用）。 */
    const val SUBMITTED_KEYWORD = "签到已提交执行"
}
