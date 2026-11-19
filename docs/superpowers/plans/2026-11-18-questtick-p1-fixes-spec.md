# QuestTick 2026-11-18 P1 审查修复规格

## 范围

修复五组已经由源码审查确认的高优先级问题：

1. StatefulRepository 构造期异步调用开放 reload，存在子类初始化竞态，并与 AppStateRepository 重复加载。
2. 运行锁过期接管后，旧运行仍可继续任务、写入进度和刷新新锁心跳；过期计时受墙钟跳变影响。
3. Worker 取消或进程中断后，业务日槽位不能安全恢复“尚未发起请求”的任务。
4. Cookie 刷新在 LToken 接口失败时仍返回成功，并把旧 LToken 覆盖为空。
5. Mask 漏处理 login_ticket_v2 与 JSON combo_token，AppLog 还将原始 Throwable 直接写入 logcat。

## 必须保持的行为

- 已发起但结果不确定的任务继续标记 RESULT_UNKNOWN，不得自动重放。
- RunPersistenceRepository.finishRun 的 Room 事务边界不得缩小。
- 手动与定时签到继续共用 SignInRunner。
- 不改变游戏选择、Root 阻断、通知和邮件的产品行为。
- 不引入新依赖，不进行大规模 UI、网络或依赖注入重构。

## 验收

- 每组修复都有先失败、后通过的 JVM 回归测试。
- 取消恢复测试明确区分“未开始可恢复”和“已发起结果未知不可重放”。
- 完整执行 spotlessCheck、:app:detekt、:app:verifyCodeRedLines、:app:testDebugUnitTest、:app:assembleDebug。
- 全部改动仅保留在本地分支，不推送、不开 PR。

## 非目标

首页错误态、账号删除事务、设置调度两阶段结果、CI 改造、HTTP 缓存策略、Keystore 并发、Compose 扫码流程迁移以及 detekt 抑制削减，列入后续 P2 工作，不在本计划中顺带处理。
