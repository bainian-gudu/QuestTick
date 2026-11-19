# QuestTick P1 Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用最小、可测试的改动修复 2026-11-18 审查确认的五组 P1 可靠性及凭证安全问题。

**Architecture:** 保留现有 UI→Repository、Worker→SignInRunner 和事务型 Outbox 结构。初始加载改由 AppStateRepository 统一触发；运行租约贯穿进度和任务边界；定时槽位通过当前 runId 关联运行记录，只恢复未发起请求的任务。

**Tech Stack:** Kotlin 2.4、Android、Kotlin Coroutines、Room、WorkManager、JUnit4、MockK、Gradle 9.4.1。

**Spec:** docs/superpowers/plans/2026-11-18-questtick-p1-fixes-spec.md

## Global Constraints

- 所有开发、测试和格式化均在 WSL Ubuntu 内执行，不读取签名或凭证文件。
- 严格 TDD：每个行为先写测试并确认按预期失败，再写最小生产实现。
- 已发起但结果不确定的任务绝不自动重放；未开始任务必须能区分并安全恢复。
- 不引入新依赖，不改变 finishRun 的原子事务，不做 P2 或大规模重构。
- 每个任务只做一个独立本地提交；不推送、不开 PR。
- 最终完整门禁必须 BUILD SUCCESSFUL，并记录 JVM 测试数、失败数和跳过数。

## Review Focus

- 子类构造尚未完成：不得发生任何自动存储读取，由 Task 1 的构造期计数测试固定。
- 旧租约恢复执行：不得发新请求、写新进度、刷新新锁或提交普通 Outbox，由 Task 2 的接管测试固定。
- 取消发生在请求前后两种位置：只允许恢复 CANCELLED/INTERRUPTED 的未开始任务，由 Task 3 的状态矩阵测试固定。
- LToken 请求失败或返回空值：不得覆盖旧凭证，由 Task 4 的失败/空值测试固定。
- 凭证出现在 Cookie、URL、JSON 或 Throwable 原因链：所有敏感值均不得原样出现，由 Task 5 的参数化场景测试固定。

---

### Task 1: 统一 Repository 初始加载所有权

**Files:**
- Modify: app/src/main/java/com/questtick/repository/base/StatefulRepository.kt
- Modify: app/src/main/java/com/questtick/repository/AppStateRepository.kt（仅在需要补 ready 语义时）
- Modify: app/src/main/java/com/questtick/work/SignInWorker.kt
- Test: app/src/test/java/com/questtick/repository/base/StatefulRepositoryTest.kt

**Interfaces:**
- Consumes: 现有 AppStateRepository 的并行 reload 与 ready: StateFlow<Boolean>。
- Produces: StatefulRepository 默认不在构造期自动加载；SignInWorker 在执行前等待 AppStateRepository.ready 首次为 true。

- [ ] **Step 1: 写失败测试** 新增 constructorDoesNotAutoLoad 和 explicitReloadLoadsExactlyOnce；测试仓库不向父类传 initialLoadEnabled，使用 runCurrent 后断言构造期读取为 0，显式 reload 后读取为 1。
- [ ] **Step 2: 验证 RED** 运行 :app:testDebugUnitTest --tests '*StatefulRepositoryTest'，确认构造期计数预期 0、实际 1。
- [ ] **Step 3: 最小实现** 将基类默认自动加载关闭，移除重复加载入口；保留 AppStateRepository 为 UI 的唯一初始加载协调者。Worker 注入 AppStateRepository，并在 doWork 开头用 ready.first { it } 等待同一初始化完成，避免后台进程使用默认设置快照。
- [ ] **Step 4: 验证 GREEN** 重跑该测试及 :app:compileDebugKotlin，确认测试通过且 Hilt/Worker 构造可编译。
- [ ] **Step 5: 本地提交** fix(repository): unify initial loading ownership。

### Task 2: 将 RunLease 绑定到执行、进度与单调时钟

**Files:**
- Modify: app/src/main/java/com/questtick/sign/SignInRunCoordinator.kt
- Modify: app/src/main/java/com/questtick/sign/SignInRunner.kt
- Modify: app/src/main/java/com/questtick/ui/vm/HomeViewModel.kt
- Modify: app/src/main/java/com/questtick/work/SignInWorker.kt
- Test: app/src/test/java/com/questtick/sign/SignInRunCoordinatorTest.kt
- Create: app/src/test/java/com/questtick/sign/RunLeaseGuardTest.kt

**Interfaces:**
- Consumes: tryRunAs 提供的 RunLease；SignInRunner.runAll 现有 isLeaseCurrent 回调。
- Produces: 进度方法接受可选 RunLease 并在失效时拒绝写入；runAll 接受可选 RunLease；内部租约守卫在随机延迟后、任务 RUNNING 前和普通 Finalizer 前检查；过期判断使用单调时钟，展示时间仍用墙钟。

- [ ] **Step 1: 写失败测试** 增加 staleLeaseCannotOverwriteTakeoverProgress、staleLeaseCannotRefreshTakeoverHeartbeat、wallClockJumpDoesNotChangeLeaseExpiry，以及守卫在租约丢失时抛出 RunLeaseLostException 的测试。
- [ ] **Step 2: 验证 RED** 运行相关两个测试类，确认旧实现允许旧租约写进度/心跳或新 API 尚不存在。
- [ ] **Step 3: 最小实现** 进度更新绑定传入租约的 token；生产默认使用 SystemClock.elapsedRealtime 计算过期，测试可注入双时钟。HomeViewModel 与 Worker 将 lease 传入 Runner；Runner 在上述三个边界拒绝继续执行，租约丢失走取消收尾，不创建普通通知/邮件 Outbox。
- [ ] **Step 4: 验证 GREEN** 重跑相关测试、SignInRunnerConcurrencyTest 和 SignInWorkerPolicyTest，确认旧租约不能跨运行写入且现有锁行为不回归。
- [ ] **Step 5: 本地提交** fix(sign): bind run lease to execution and progress。

### Task 3: 安全恢复取消后的业务日槽位

**Files:**
- Modify: app/src/main/java/com/questtick/sign/SignInRunner.kt
- Modify: app/src/main/java/com/questtick/work/SignInWorker.kt
- Modify: app/src/main/java/com/questtick/repository/run/RunPersistenceRepository.kt（仅复用恢复入口，不改变事务）
- Modify: app/src/main/java/com/questtick/repository/run/ExecutionStateRepository.kt
- Modify: app/src/main/java/com/questtick/data/db/ExecutionStateDao.kt
- Create: app/src/main/java/com/questtick/repository/run/ExecutionStateRecovery.kt
- Create: app/src/test/java/com/questtick/repository/run/ExecutionStateRecoveryTest.kt
- Modify: app/src/androidTest/java/com/questtick/data/db/ExecutionStateDatabaseTest.kt（补数据库集成回归；无设备时明确标记未执行）

**Interfaces:**
- Consumes: RunPersistenceRepository 的 RUN_STATUS_CANCELLED/INTERRUPTED、TASK_STATUS_CANCELLED/INTERRUPTED 和 RunDao.getTasks。
- Produces: runAll 同时接受 Task 2 的可选 RunLease 与本任务的可选 runId；markScheduledRunStarted(workId, businessDayKey, runId) 将槽位与当前运行关联；interruptedRetryTaskIds 只返回未开始且已取消/中断的任务 ID。

- [ ] **Step 1: 写失败测试** 用取消、进程中断、仍 RUNNING、runId 已登记但运行尚未落库、已有历史五种状态矩阵验证：未开始任务可恢复，RESULT_UNKNOWN 不可恢复，活跃 RUNNING 不可被接管，未落库运行可完整恢复，历史记录仍沿用原 retryable 策略。
- [ ] **Step 2: 验证 RED** 运行 :app:testDebugUnitTest --tests '*ExecutionStateRecoveryTest'，确认新恢复策略尚不存在或旧槽位返回空任务集。
- [ ] **Step 3: 最小实现** Worker 在领取槽位前调用 recoverInterruptedRuns，确保进程遗留 RUNNING 先转为 INTERRUPTED；随后在运行前生成 runId 并写入槽位，再传给 Runner。claimScheduledSlot 在同事务确认关联运行已为 CANCELLED/INTERRUPTED，或 runId 已登记但运行尚未落库后允许接管；retryableTaskIdsForSlot 对未落库运行返回 null（完整计划），对终态运行只返回未开始任务白名单，活跃或无可靠记录时继续保守返回空集。
- [ ] **Step 4: 验证 GREEN** 重跑恢复测试与 SignInWorkerPolicyTest，再编译 androidTest 源码；有设备时执行 ExecutionStateDatabaseTest，无设备则记录未执行。
- [ ] **Step 5: 本地提交** fix(work): recover unstarted tasks after cancellation。

### Task 4: 保留 LToken 刷新失败前的凭证

**Files:**
- Modify: app/src/main/java/com/questtick/sign/CookieRefresher.kt
- Test: app/src/test/java/com/questtick/sign/CookieRefresherTest.kt

**Interfaces:**
- Consumes: fetchCookieToken 与 fetchLtoken 的现有响应。
- Produces: CookieRefresher.refresh 在 LToken 请求失败、非零 retcode 或空值时返回 null，不产出可覆盖旧值的部分刷新结果。

- [ ] **Step 1: 写失败测试** 分别模拟 LToken 非零 retcode、异常和空 data，断言 refresh 返回 null，且已请求过 cookie_token 接口。
- [ ] **Step 2: 验证 RED** 运行 CookieRefresherTest，确认旧实现返回 ltoken 为空的对象。
- [ ] **Step 3: 最小实现** 在构造 RefreshedCookie 前要求 LToken 非空，不修改成功路径及调用方签名。
- [ ] **Step 4: 验证 GREEN** 重跑 CookieRefresherTest、AuthRepositoryTest 和 SignInCredentialCoordinatorDecisionTest。
- [ ] **Step 5: 本地提交** fix(sign): reject partial cookie refresh。

### Task 5: 补齐凭证脱敏与 Throwable 过滤

**Files:**
- Modify: app/src/main/java/com/questtick/sign/Mask.kt
- Modify: app/src/main/java/com/questtick/log/AppLog.kt
- Test: app/src/test/java/com/questtick/sign/MaskTest.kt
- Create: app/src/test/java/com/questtick/log/AppLogTest.kt

**Interfaces:**
- Consumes: Mask.sensitive(String?) 与 Throwable.stackTraceToString()。
- Produces: login_ticket_v2、JSON combo_token 的脱敏规则；AppLog.internal fun sanitizedThrowable(throwable: Throwable): String 输出经 Mask 处理的异常类型、堆栈和原因链，不再把原始 Throwable 传给 android.util.Log。

- [ ] **Step 1: 写失败测试** 覆盖 login_ticket_v2 的 Cookie/URL/JSON 形态、combo_token 的 JSON 形态，以及嵌套 Throwable 中含凭证时 sanitizedThrowable 不泄露原值。
- [ ] **Step 2: 验证 RED** 运行 MaskTest 和 AppLogTest，确认秘密值仍可被检出。
- [ ] **Step 3: 最小实现** 增加精确规则并避免误伤普通文本；AppLog 将 stackTraceToString 经统一 Mask 后作为日志文本输出，保留异常类型、堆栈与原因链。
- [ ] **Step 4: 验证 GREEN** 重跑 MaskTest、AppLogTest、LogExporterTest 和 AppErrorLoggerTest。
- [ ] **Step 5: 本地提交** fix(log): mask remaining credential forms。

### Task 6: 全量验证与最终审查

**Files:**
- Review: 本计划全部改动。

**Interfaces:**
- Consumes: Task 1–5 的本地提交与测试证据。

- [ ] **Step 1: 审查差异** 检查 git log、git diff 和每个新测试的 RED/GREEN 记录，确认无越界改动、无凭证文件进入差异。
- [ ] **Step 2: 运行格式与全量门禁** 执行 ./gradlew spotlessApply spotlessCheck :app:detekt :app:verifyCodeRedLines :app:testDebugUnitTest :app:assembleDebug --console=plain --rerun-tasks，若 spotlessApply 改文件则复查并补交格式变更。
- [ ] **Step 3: 记录结果** 汇总提交号、变更文件、JVM 测试总数/失败/跳过、未执行的 androidTest，以及仍需后续处理的 P2；不得推送。
