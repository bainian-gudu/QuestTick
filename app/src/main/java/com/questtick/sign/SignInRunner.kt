package com.questtick.sign

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.questtick.config.DsConfigRepository
import com.questtick.core.throwIfCancellation
import com.questtick.data.Account
import com.questtick.data.AppSettings
import com.questtick.data.FailureCategory
import com.questtick.data.Games
import com.questtick.data.LogEntry
import com.questtick.data.RunRecord
import com.questtick.data.RunTerminationReason
import com.questtick.data.RunTrigger
import com.questtick.data.SecureStore
import com.questtick.data.TaskResult
import com.questtick.repository.account.AccountRepository
import com.questtick.net.HttpTransport
import com.questtick.repository.auth.AuthRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.calendar.SignInCalendarRepository
import com.questtick.repository.log.LogRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.repository.run.RunPersistenceRepository
import com.questtick.repository.run.PostRunActionRequest
import com.questtick.repository.run.PostRunActionType
import com.questtick.work.PostRunActionScheduler
import com.questtick.security.RootBlockingPolicy
import com.questtick.security.RootDetectorV2
import com.questtick.security.RootEnvironmentChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private class RunPersistenceException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private data class MysRuntimePreparation(
    val appVersion: String,
    val deviceId: String,
)

private data class CloudRuntimePreparation(
    val deviceId: String,
)

/**
 * 签到编排器。
 *
 * 负责账号级调度、运行诊断、结果归并与收尾落盘；
 * 具体的米游社与云游戏执行流程分别委托给独立执行器处理。
 * 同一账号内的游戏任务保持串行，不同账号之间可按配置并行执行。
 *
 * @param parallel 为 false 时退化为串行模式，便于调试与排障。
 */
class SignInRunner(
    private val context: Context,
    private val store: SecureStore,
    private val accountRepository: AccountRepository,
    private val historyRepository: HistoryRepository,
    private val signInCalendarRepository: SignInCalendarRepository,
    private val logRepository: LogRepository,
    private val runPersistenceRepository: RunPersistenceRepository,
    private val rootEnvironmentChecker: RootEnvironmentChecker,
    private val httpTransport: HttpTransport,
    authRepository: AuthRepository,
    private val postRunActionScheduler: PostRunActionScheduler,
    private val parallel: Boolean = false,
    private val runCoordinator: SignInRunCoordinator? = null,
    private val appErrorLogger: AppErrorLogger? = null,
) {
    // 并发执行时通过线程安全队列收集日志；任务结果由单次运行专属收集器保证唯一终态。
    private val logs = ConcurrentLinkedQueue<LogEntry>()

    // act_id 缓存会在运行中被刷新，使用 Mutex 保护并发写入。
    private val actIdCacheMutex = Mutex()

    // 米游社与云游戏版本并行刷新时，整份设置必须串行合并，避免后写入的一方覆盖另一方。
    private val settingsUpdateMutex = Mutex()

    // 账号并行度按 CPU 核心数收敛到较小范围，避免后台任务过度抢占资源。
    private val maxParallelism = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /** 请求级限流器：协程可以并发调度，但真正发起请求前仍需排队。 */
    private val mysRequestLimiter = Semaphore(permits = 2)
    private val cloudRequestLimiter = Semaphore(permits = 1)
    private val refreshRequestLimiter = Semaphore(permits = 1)

    @Volatile
    private var debugLoggingEnabled: Boolean = false

    private val cloudGameBindings =
        listOf(
            CloudGameBinding(
                game = CloudSignIn.GAMES.getValue("CloudYS"),
                tokenOf = { it.genshinToken },
                webCookieOf = { it.genshinWebCookie },
                hasKeepLogin = { it.hasGenshinCloudKeepLogin },
                updateToken = { account, token -> account.copy(genshinToken = token) },
            ),
            CloudGameBinding(
                game = CloudSignIn.GAMES.getValue("CloudSR"),
                tokenOf = { it.starrailToken },
                webCookieOf = { it.starrailWebCookie },
                hasKeepLogin = { it.hasStarrailCloudKeepLogin },
                updateToken = { account, token -> account.copy(starrailToken = token) },
            ),
        )

    private fun hasCloudYS(account: Account): Boolean = account.hasCloudTask(cloudGameBindings[0])

    private fun hasCloudSR(account: Account): Boolean = account.hasCloudTask(cloudGameBindings[1])

    private val credentialCoordinator =
        SignInCredentialCoordinator(
            authRepository = authRepository,
            accountRepository = accountRepository,
            refreshLimiter = refreshRequestLimiter,
            log = ::log,
        )
    private val mysSignExecutor =
        MysSignExecutor(
            credentialCoordinator = credentialCoordinator,
            httpTransport = httpTransport,
            requestLimiter = mysRequestLimiter,
            log = ::log,
            recordError = { feature, detail ->
                appErrorLogger?.record(feature, IllegalStateException(detail), detail)
            },
        )
    private val cloudSignExecutor =
        CloudSignExecutor(
            credentialCoordinator = credentialCoordinator,
            httpTransport = httpTransport,
            requestLimiter = cloudRequestLimiter,
            log = ::log,
            recordError = { feature, detail ->
                appErrorLogger?.record(feature, IllegalStateException(detail), detail)
            },
        )

    private fun logPlannedTasksForAccount(account: Account) {
        val mysGames = account.selectedMysGames()
        val cloudGames = account.selectedCloudBindings(cloudGameBindings).map { it.game.name }
        val gameNames = (mysGames.map { Games.byKey(it.key)?.name ?: it.name } + cloudGames).joinToString("、")
        log(
            "INFO",
            "[${account.label}] 待签游戏: $gameNames（共 ${mysGames.size + cloudGames.size} 款）",
            "hasCookie=${account.mysCookie.isNotBlank()}, hasMysKeepLogin=${account.hasMysKeepLogin}, " +
                "hasGenshinToken=${account.genshinToken.isNotBlank()}, hasStarrailToken=${account.starrailToken.isNotBlank()}, " +
                "hasGenshinWebCookie=${account.genshinWebCookie.isNotBlank()}, " +
                "hasStarrailWebCookie=${account.starrailWebCookie.isNotBlank()}, qrLoginBound=${account.qrLoginBound}",
        )
    }

    private fun log(
        level: String,
        message: String,
        detail: String = "",
    ) {
        if (level.uppercase() == "DEBUG" && !debugLoggingEnabled) return
        val entry =
            LogEntry(
                System.currentTimeMillis(),
                level,
                Mask.sensitive(message),
                Mask.sensitive(detail),
            )
        logs.add(entry)
        // 同步到 LogRepository，让日志页实时显示。
        logRepository.append(entry)
    }

    private suspend fun accountStartJitter(
        accountLabel: String,
        index: Int,
        total: Int,
    ) {
        if (!parallel || total <= 1) return
        val delayMs = Random.nextLong(800L, 3501L) + index * Random.nextLong(250L, 701L)
        log("INFO", "账号启动随机延迟：[$accountLabel] ${delayMs}ms", "accountIndex=$index, totalAccounts=$total")
        delay(delayMs)
    }

    /** 记录系统与运行环境信息，供详细日志排查使用。 */
    private fun logSystemInfo(
        settings: AppSettings,
        accounts: List<Account>,
        rootResultForLog: com.questtick.security.RootDetectorV2.RootCheckResult?,
    ) {
        val appVersion =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L)).versionName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }
            } catch (_: Exception) {
                "unknown"
            }

        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val model = Build.MODEL ?: "unknown"
        val androidVersion = Build.VERSION.RELEASE ?: "unknown"
        val sdk = Build.VERSION.SDK_INT
        // Root 检测为强制安全基线；使用本次签到前的实时检测结果写入日志。
        val rootState =
            rootResultForLog?.let {
                when {
                    it.isRooted -> {
                        "检测到Root [${it.level} score=${it.score} ${it.rootEvidenceTriggers.take(3).joinToString()}]"
                    }
                    it.completeness == RootDetectorV2.CheckCompleteness.FAILED -> {
                        "Root检测失败 [checked=${it.checkedProbeCount}/${RootDetectorV2.TOTAL_PROBE_GROUPS} unavailable=${it.unavailableProbes.take(3).joinToString()}]"
                    }
                    it.completeness == RootDetectorV2.CheckCompleteness.PARTIAL -> {
                        "Root检测部分降级（不单独阻断） [checked=${it.checkedProbeCount}/${RootDetectorV2.TOTAL_PROBE_GROUPS} unavailable=${it.unavailableProbes.take(3).joinToString()}]"
                    }
                    it.isEmulator -> {
                        "模拟器环境（不阻断） [score=${it.score} ${it.triggers.take(3).joinToString()}]"
                    }
                    it.triggers.isNotEmpty() -> {
                        "检测到非Root风险项（不阻断） [${it.level} score=${it.score} ${it.triggers.take(3).joinToString()}]"
                    }
                    else -> "未检测到Root"
                }
            } ?: "检测失败"

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
        val notificationGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        val enabledCount = accounts.count { it.enabled }
        val withMysCookie = accounts.count { it.mysCookie.isNotBlank() }
        val withMysKeepLogin = accounts.count { it.hasMysKeepLogin }
        val withMysCredential = accounts.count { it.mysCookie.isNotBlank() || it.hasMysKeepLogin }
        val withCloudYs = accounts.count { it.genshinToken.isNotBlank() || it.hasGenshinCloudKeepLogin }
        val withCloudSr = accounts.count { it.starrailToken.isNotBlank() || it.hasStarrailCloudKeepLogin }
        val withCloudToken = withCloudYs + withCloudSr

        val schedule =
            if (settings.scheduleEnabled) {
                "${settings.scheduleHour.toString().padStart(2, '0')}:${settings.scheduleMinute.toString().padStart(2, '0')}"
            } else {
                "未启用"
            }

        val experiments =
            buildList {
                if (settings.actIdAutoRefresh) add("act_id自动刷新")
                if (settings.parallelEnabled) add("并行签到")
                if (settings.mysAppVersionAutoFetch) add("米游社版本自动获取")
                if (settings.mysAppVersion.isNotBlank()) add("米游社自定义版本=${settings.mysAppVersion}")
                if (settings.cloudVersionAutoFetch) {
                    add(
                        "云游戏版本自动获取(" +
                            "ys=${CloudVersionRepository.effectiveYs()}, " +
                            "sr=${CloudVersionRepository.effectiveSr()})",
                    )
                }
                if (settings.cloudYsVersion.isNotBlank()) add("云原神自定义版本=${settings.cloudYsVersion}")
                if (settings.cloudSrVersion.isNotBlank()) add("云崩铁自定义版本=${settings.cloudSrVersion}")
                if (settings.debugLoggingEnabled) add("调试日志")
                add("root检测=强制开启，检测到Root后阻断")
            }.joinToString(", ").ifEmpty { "无" }

        val mail = store.getMailSettings()
        val mysDeviceId = store.effectiveMysDeviceId(forceCreate = false)
        val cloudDeviceId = store.effectiveCloudDeviceId(forceCreate = false)
        val dsConfig = DsConfigRepository.current

        log(
            "INFO",
            "系统/运行环境信息",
            buildString {
                append("appVersion=$appVersion, ")
                append("android=$androidVersion (API $sdk), ")
                append("device=$manufacturer $model, ")
                append("rootState=$rootState, ")
                append("batteryOptimizationIgnored=$batteryOptIgnored, ")
                append("notificationPermissionGranted=$notificationGranted, ")
                append(
                    "accounts={total=${accounts.size}, enabled=$enabledCount, " +
                        "withMysCookie=$withMysCookie, withMysKeepLogin=$withMysKeepLogin, " +
                        "withMysCredential=$withMysCredential, withCloudYS=$withCloudYs, " +
                        "withCloudSR=$withCloudSr, withCloudToken=$withCloudToken}, ",
                )
                append("schedule={enabled=${settings.scheduleEnabled}, time=$schedule}, ")
                append("notifyEnabled=${settings.notifyEnabled}, ")
                append("mailEnabled=${mail.enabled}, ")
                append("experiments={$experiments}, ")
                append("dsConfig={version=${dsConfig.version}, algorithm=${dsConfig.algorithm}, updateTime=${dsConfig.updateTime}}, ")
                append("mysDeviceId=${if (mysDeviceId.isNotBlank()) "***${mysDeviceId.takeLast(4)}" else "未生成"}, ")
                append("cloudDeviceId=${if (cloudDeviceId.isNotBlank()) "***${cloudDeviceId.takeLast(4)}" else "未生成"}")
            },
        )
    }

    // 对外入口。
    suspend fun runAll(
        onProgress: (label: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
        precheckedRootResult: RootDetectorV2.RootCheckResult? = null,
        rootCheckAlreadyPerformed: Boolean = false,
        trigger: RunTrigger = RunTrigger.MANUAL,
        allowedTaskIds: Set<String>? = null,
        excludedAccountIds: Set<String> = emptySet(),
    ): RunRecord {
        val runId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        // 启动新会话前先幂等恢复旧 RUNNING 会话，避免进程中断后的签到 POST 被盲目重放。
        val recovered = withContext(Dispatchers.IO) { runPersistenceRepository.recoverInterruptedRuns() }
        if (recovered.recoveredRuns > 0) {
            historyRepository.reload()
            signInCalendarRepository.reload()
            logRepository.reload()
        }
        val allAccounts = withContext(Dispatchers.IO) { accountRepository.getAccounts() }
        val enabledAccounts = allAccounts.filter { it.enabled && it.id !in excludedAccountIds }
        if (allAccounts.isEmpty() || enabledAccounts.isEmpty()) {
            onProgress("没有启用账号", 0, 0)
            runCoordinator?.finishProgress(RunProgressPhase.FINISHED, "没有启用账号")
            return RunRecord(
                timestamp = System.currentTimeMillis(),
                results = emptyList(),
                runId = runId,
                trigger = trigger,
                terminationReason = RunTerminationReason.NO_ENABLED_ACCOUNT,
            )
        }
        // 每次运行随机化账号顺序，减少长期固定请求轨迹带来的风控特征。
        val accounts = enabledAccounts.shuffled(Random(System.nanoTime()))
        // 定时签到复用当天手动签到结果；已确认成功的任务不再重复请求，失败任务仍可按定时重试策略处理。
        val signedTaskIdsForDay =
            if (trigger == RunTrigger.SCHEDULED || trigger == RunTrigger.RETRY) {
                runPersistenceRepository.signedTaskIdsForDay(startTime)
            } else {
                emptySet()
            }
        val taskPlan =
            buildRunTaskPlan(accounts, cloudGameBindings)
                .filter { task -> allowedTaskIds == null || task.id in allowedTaskIds }
                .filter { task -> task.id !in signedTaskIdsForDay }
        val plannedTaskIds = taskPlan.mapTo(linkedSetOf()) { it.id }
        val resultAccumulator = TaskResultAccumulator(plannedTaskIds)
        runCoordinator?.setTaskPlan(taskPlan)

        val total = taskPlan.size
        if (total == 0) {
            onProgress("没有可执行任务", 0, 0)
            runCoordinator?.finishProgress(RunProgressPhase.FINISHED, "没有可执行任务")
            return RunRecord(
                timestamp = System.currentTimeMillis(),
                results = emptyList(),
                runId = runId,
                trigger = trigger,
                terminationReason = RunTerminationReason.NO_RUNNABLE_TASK,
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                runPersistenceRepository.startRun(runId, trigger, startTime, taskPlan)
            }
        val uncertainTaskIds =
            withContext(Dispatchers.IO) {
                runPersistenceRepository.uncertainTaskIdsForDay(startTime).intersect(taskPlan.map { it.id }.toSet())
            }

        val workerParallelism =
            if (parallel) {
                minOf(accounts.size.coerceAtLeast(1), maxParallelism)
            } else {
                1
            }

            withContext(Dispatchers.IO.limitedParallelism(workerParallelism)) {
            logs.clear()

            val settings = store.getAppSettings()
            // 将设置页自定义云游戏版本同步到生效版本，确保签到请求使用用户指定的版本号。
            CloudVersionRepository.applyOverrides(settings.cloudYsVersion, settings.cloudSrVersion)
            debugLoggingEnabled = settings.debugLoggingEnabled
            // 版本自动获取必须由本次真正可执行的对应签到任务触发，不能只看账号是否保存了凭证。
            val hasMysTasks =
                taskPlan.any { it.type == RunTaskType.MYS && it.id !in uncertainTaskIds } ||
                    accounts.any { MysCoinCheckIn.featureEnabled && it.mysCoinEnabled }
            val hasCloudYsTasks =
                taskPlan.any {
                    it.type == RunTaskType.CLOUD && it.id !in uncertainTaskIds &&
                        it.id.substringAfterLast('|') == "CloudYS"
                }
            val hasCloudSrTasks =
                taskPlan.any {
                    it.type == RunTaskType.CLOUD && it.id !in uncertainTaskIds &&
                        it.id.substringAfterLast('|') == "CloudSR"
                }
            val hasCloudTasks = hasCloudYsTasks || hasCloudSrTasks

            // 先写入运行起点，让版本准备、Root 检测和后续任务日志都落入同一签到组。
            log("INFO", "========== 开始执行签到 ==========")
            log(
                "DEBUG",
                "调试日志已启用",
                "enabledAccounts=${accounts.size}, totalAccounts=${allAccounts.size}, workerParallelism=$workerParallelism, " +
                    "hasMysTasks=$hasMysTasks, hasCloudTasks=$hasCloudTasks",
            )

            val actIdCache = ConcurrentHashMap<String, String>()
            actIdCache.putAll(store.getActIdCache())

            val done = AtomicInteger(0)
            val hadUnexpectedAccountFailure = AtomicBoolean(false)
            val completeTask: (String, TaskResult, String) -> Unit = { taskId, result, progressLabel ->
                val normalizedResult =
                    result.copy(
                        taskId = taskId,
                        accountId = result.accountId.ifBlank { taskId.substringBefore('|') },
                    )
                when (resultAccumulator.accept(taskId, normalizedResult)) {
                    TaskResultAcceptance.ACCEPTED -> {
                        val persisted =
                            try {
                                runPersistenceRepository.persistTaskResult(runId, taskId, normalizedResult)
                            } catch (e: Exception) {
                                throw RunPersistenceException(
                                    "任务终态持久化异常: $taskId (${e.javaClass.simpleName})",
                                    e,
                                )
                            }
                        if (!persisted) {
                            throw RunPersistenceException("任务终态持久化失败或已存在: $taskId")
                        }
                        markProgressTaskFinished(taskId, normalizedResult)
                        val completed = done.incrementAndGet()
                        onProgress(progressLabel, completed.coerceAtMost(total), total)
                    }
                    TaskResultAcceptance.DUPLICATE -> {
                        hadUnexpectedAccountFailure.set(true)
                        log(
                            "WARN",
                            "忽略重复的任务终态结果",
                            "taskId=$taskId, gameKey=${result.gameKey}, accountId=${result.accountId.takeLast(8)}",
                        )
                    }
                    TaskResultAcceptance.UNPLANNED -> {
                        hadUnexpectedAccountFailure.set(true)
                        log(
                            "ERROR",
                            "忽略未列入计划的任务结果",
                            "taskId=$taskId, gameKey=${result.gameKey}, accountId=${result.accountId.takeLast(8)}",
                        )
                    }
                }
            }
            val shouldExecuteTask: (String) -> Boolean = { taskId ->
                resultAccumulator.isExpected(taskId) && !resultAccumulator.contains(taskId)
            }
            val isTaskRunning: (String) -> Boolean = { taskId ->
                try {
                    runPersistenceRepository.isTaskRunning(runId, taskId)
                } catch (e: Exception) {
                    throw RunPersistenceException(
                        "读取任务持久化状态异常: $taskId (${e.javaClass.simpleName})",
                        e,
                    )
                }
            }
            val startTask: (String, String) -> Boolean = { taskId, message ->
                if (!shouldExecuteTask(taskId)) {
                    false
                } else {
                    val started =
                        try {
                            runPersistenceRepository.markTaskRunning(runId, taskId, message)
                        } catch (e: Exception) {
                            throw RunPersistenceException(
                                "任务执行权持久化异常: $taskId (${e.javaClass.simpleName})",
                                e,
                            )
                        }
                    if (!started) throw RunPersistenceException("无法获取任务执行权: $taskId")
                    markProgressTaskRunning(taskId, message)
                    true
                }
            }

            runCoordinator?.setProgressPhase(RunProgressPhase.CHECKING, "正在检查运行环境")
            // Root 阻断必须使用强制实时检测，不能复用短期缓存，避免 Root 状态变化后继续放行。
            val rootResult =
                if (rootCheckAlreadyPerformed) {
                    precheckedRootResult
                } else {
                    try {
                        rootEnvironmentChecker.check(forceRefresh = true)
                    } catch (e: Throwable) {
                        e.throwIfCancellation()
                        log(
                            "ERROR",
                            RootBlockMessages.CheckFailedContent,
                            "${e.javaClass.simpleName}: ${e.message}",
                        )
                        null
                    }
                }
            if (rootCheckAlreadyPerformed && rootResult == null) {
                log(
                    "ERROR",
                    RootBlockMessages.CheckFailedContent,
                    "manual pre-check failed before runner start",
                )
            }
            logSystemInfo(settings, allAccounts, rootResult)
            val rootDecision = RootBlockingPolicy.decide(rootResult)
            if (rootDecision != RootBlockingPolicy.Decision.ALLOW) {
                val rootCheckFailed = rootDecision == RootBlockingPolicy.Decision.BLOCK_CHECK_FAILED
                val blockMessage = rootBlockResultMessage(rootDecision)
                log(
                    "ERROR",
                    blockMessage,
                    rootResult?.let {
                        "completeness=${it.completeness}, checked=${it.checkedProbeCount}/${RootDetectorV2.TOTAL_PROBE_GROUPS}, " +
                            "unavailable=${it.unavailableProbes.take(5).joinToString()}, score=${it.score}, " +
                            "rootEvidence=${it.rootEvidenceTriggers.take(5).joinToString()}, triggers=${it.triggers.take(5).joinToString()}"
                    } ?: "root check unavailable",
                )
                val blockedResults =
                    sortTaskResultsForRecord(
                        accounts.flatMap { account ->
                            buildSkippedTaskResults(
                                account = account,
                                message = blockMessage,
                                cloudGameBindings = cloudGameBindings,
                                failure =
                                    TaskFailureDescriptor(
                                        category = FailureCategory.SECURITY_BLOCKED,
                                        errorCode = if (rootCheckFailed) "root-check-failed" else "root-detected",
                                    ),
                            ).filter { it.taskId in plannedTaskIds }
                        },
                    )
                blockedResults.forEach { result ->
                    completeTask(result.taskId, result, "${result.accountLabel} · ${result.game}")
                    logOutcome(
                        account = result.accountLabel,
                        game = result.game,
                        success = false,
                        skipped = true,
                        message = result.message,
                        detail = "blockedBy=rootCheck",
                    )
                }
                onProgress(blockMessage, 0, total)
                runCoordinator?.skipUnfinishedTasks(blockMessage)
                val record =
                    RunRecord(
                        timestamp = System.currentTimeMillis(),
                        results = blockedResults,
                        runId = runId,
                        trigger = trigger,
                        terminationReason =
                            if (rootCheckFailed) {
                                RunTerminationReason.ROOT_CHECK_FAILED
                            } else {
                                RunTerminationReason.ROOT_DETECTED
                            },
                    )
                runCoordinator?.setProgressPhase(RunProgressPhase.SAVING, "正在保存阻断结果")
                finish(record, actIdCache, startTime, enqueuePostRunActions = false)
                runCoordinator?.finishProgress(RunProgressPhase.BLOCKED, blockMessage)
                return@withContext record
            }

            // 上次进程中断时已经发出请求但没有结果的任务，本业务日不再自动重放。
            if (uncertainTaskIds.isNotEmpty()) {
                taskPlan.filter { it.id in uncertainTaskIds }.forEach { task ->
                    val parts = task.id.split('|', limit = 3)
                    completeTask(
                        task.id,
                        TaskResult(
                            game = task.targetName,
                            gameKey = parts.getOrNull(2).orEmpty(),
                            accountLabel = task.accountLabel,
                            accountId = parts.getOrNull(0).orEmpty(),
                            success = false,
                            skipped = true,
                            message = RunPersistenceRepository.RESULT_UNKNOWN_MESSAGE,
                            failureCategory = FailureCategory.RESULT_UNKNOWN,
                            errorCode = "previous-run-result-unknown",
                            retryable = false,
                        ),
                        "${task.accountLabel} · ${task.targetName}（结果待确认）",
                    )
                }
            }

            runCoordinator?.setProgressPhase(RunProgressPhase.REFRESHING, "正在准备版本与凭证")
            // Root 已确认放行后再启动准备任务。这里的并行独立于“并行签到”开关；
            // 该开关仍只控制多账号处理，米游社与云游戏请求各自等待自己的准备结果。
            val mysPreparation = async {
                prepareMysRuntime(settings, hasMysTasks)
            }
            val cloudPreparation = async {
                prepareCloudRuntime(settings, hasCloudYsTasks, hasCloudSrTasks, hasCloudTasks)
            }
            log(
                "DEBUG",
                "运行配置快照",
                buildString {
                    append("parallel=${settings.parallelEnabled}, ")
                    append("actIdAutoRefresh=${settings.actIdAutoRefresh}, ")
                    append("mysAppVersion={autoFetch=${settings.mysAppVersionAutoFetch}, ")
                    append("custom=${settings.mysAppVersion.ifBlank { "<empty>" }}, ")
                    append("effective=${MysAppVersionRepository.effectiveVersion(settings.mysAppVersion).ifBlank { "<empty>" }}}, ")
                    append("cloudVersion={autoFetch=${settings.cloudVersionAutoFetch}, ")
                    append("ysCustom=${settings.cloudYsVersion.ifBlank { "<empty>" }}, ")
                    append("srCustom=${settings.cloudSrVersion.ifBlank { "<empty>" }}, ")
                    append("ysEffective=${CloudVersionRepository.effectiveYs()}, ")
                    append("srEffective=${CloudVersionRepository.effectiveSr()}}, ")
                    append("rootCheck={enabled=true, mode=evidence, blockRun=true}, ")
                    append("actIdCacheKeys=${actIdCache.keys.joinToString()}")
                },
            )

            val cpuCores = Runtime.getRuntime().availableProcessors()
            val maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024
            val freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024
            val mode = if (parallel && accounts.size > 1) "多核并行（${cpuCores}核）" else "串行"
            val mysVersion =
                when {
                    settings.mysAppVersion.isNotBlank() -> "自定义(${settings.mysAppVersion})"
                    settings.mysAppVersionAutoFetch ->
                        "自动准备中(${MysAppVersionRepository.effectiveVersion(settings.mysAppVersion)})"
                    else -> "默认(${MysAppVersionRepository.currentVersion})"
                }

            log(
                "INFO",
                "运行模式: $mode, 启用账号: ${accounts.size} 个",
                "parallel=$parallel, cpuCores=$cpuCores, maxHeap=${maxMem}MB, freeHeap=${freeMem}MB, " +
                    "thread=${Thread.currentThread().name}",
            )
            log("INFO", "米游社版本准备已并行启动", "configured=$mysVersion")
            if (settings.actIdAutoRefresh) {
                log("INFO", "act_id 自动刷新已开启")
            }
            if (settings.parallelEnabled) {
                log("INFO", "并行调度线程上限: $workerParallelism")
            }

            log("INFO", "任务总数: $total 个游戏签到（${accounts.size} 个账号）")

            accounts.forEach(::logPlannedTasksForAccount)
            runCoordinator?.setProgressPhase(RunProgressPhase.SIGNING, "准备开始签到任务")

            if (parallel && accounts.size > 1) {
                log("INFO", "────── 启动并行调度，${accounts.size} 个协程同时执行 ──────")
                coroutineScope {
                    accounts.mapIndexed { index, acc ->
                        async {
                            try {
                                accountStartJitter(acc.label, index, accounts.size)
                                processAccount(
                                    acc,
                                    settings,
                                    mysPreparation,
                                    cloudPreparation,
                                    actIdCache,
                                    total,
                                    done,
                                    onProgress,
                                    shouldExecuteTask = shouldExecuteTask,
                                    onTaskStarted = startTask,
                                    onTaskCompleted = completeTask,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e.throwIfCancellation()
                                if (e is RunPersistenceException) throw e
                                log(
                                    "ERROR",
                                    "[${acc.label}] 账号处理异常终止",
                                    ErrorText.detailOf(e, includeStackTrace = debugLoggingEnabled),
                                )
                                hadUnexpectedAccountFailure.set(true)
                                addFailedResultsForAccount(
                                    acc,
                                    e,
                                    { taskId -> !resultAccumulator.isExpected(taskId) || resultAccumulator.contains(taskId) },
                                    isTaskRunning,
                                    completeTask,
                                )
                            }
                        }
                    }.awaitAll()
                }
            } else {
                for (acc in accounts) {
                    try {
                        processAccount(
                            acc,
                            settings,
                            mysPreparation,
                            cloudPreparation,
                            actIdCache,
                            total,
                            done,
                            onProgress,
                            shouldExecuteTask = shouldExecuteTask,
                            onTaskStarted = startTask,
                            onTaskCompleted = completeTask,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.throwIfCancellation()
                        if (e is RunPersistenceException) throw e
                        log(
                            "ERROR",
                            "[${acc.label}] 账号处理异常终止",
                            ErrorText.detailOf(e, includeStackTrace = debugLoggingEnabled),
                        )
                        hadUnexpectedAccountFailure.set(true)
                        addFailedResultsForAccount(
                            acc,
                            e,
                            { taskId -> !resultAccumulator.isExpected(taskId) || resultAccumulator.contains(taskId) },
                            isTaskRunning,
                            completeTask,
                        )
                    }
                }
            }

            if (resultAccumulator.size != total) {
                hadUnexpectedAccountFailure.set(true)
                log(
                    "ERROR",
                    "签到任务结果不完整，正在补齐未完成任务",
                    "planned=$total, completed=${resultAccumulator.size}",
                )
                accounts.forEach { account ->
                    buildFailedTaskResults(
                        account = account,
                        errorMessage = "任务执行异常中断",
                        cloudGameBindings = cloudGameBindings,
                        failure = TaskFailureDescriptor(FailureCategory.INTERNAL_ERROR, "missing-terminal-result"),
                    ).forEach { result ->
                        val taskId = taskIdForResult(account, result, cloudGameBindings)
                        if (resultAccumulator.isExpected(taskId) && !resultAccumulator.contains(taskId)) {
                            val finalResult =
                                if (isTaskRunning(taskId)) {
                                    result.asUnknownAfterRequest("missing-terminal-after-request")
                                } else {
                                    result
                                }
                            completeTask(taskId, finalResult, "${account.label} · 异常收尾")
                        }
                    }
                }
            }
            onProgress("完成", resultAccumulator.size.coerceAtMost(total), total)

            val sortedResults = sortTaskResultsForRecord(resultAccumulator.values())
            val record =
                RunRecord(
                    timestamp = System.currentTimeMillis(),
                    results = sortedResults,
                    runId = runId,
                    trigger = trigger,
                    terminationReason =
                        if (hadUnexpectedAccountFailure.get()) {
                            RunTerminationReason.INTERNAL_ERROR
                        } else {
                            RunTerminationReason.COMPLETED
                        },
                )
            runCoordinator?.setProgressPhase(RunProgressPhase.SAVING, "正在保存签到结果")
            finish(record, actIdCache, startTime)
                runCoordinator?.finishProgress(RunProgressPhase.FINISHED, "签到任务已完成")
                record
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    runPersistenceRepository.cancelRun(
                        runId = runId,
                        reason =
                            if (trigger == RunTrigger.MANUAL) {
                                RunTerminationReason.USER_CANCELLED
                            } else {
                                RunTerminationReason.SYSTEM_CANCELLED
                            },
                        logs = logs.toList().sortedBy { it.timestamp },
                    )
                } catch (cleanupError: Exception) {
                    e.addSuppressed(cleanupError)
                }
            }
            throw e
        } catch (e: Exception) {
            log(
                "ERROR",
                "签到运行异常",
                ErrorText.detailOf(e, includeStackTrace = debugLoggingEnabled),
            )
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    runPersistenceRepository.failRun(
                        runId = runId,
                        logs = logs.toList().sortedBy { it.timestamp },
                    )
                } catch (cleanupError: Exception) {
                    e.addSuppressed(cleanupError)
                }
            }
            throw e
        }
    }

    private suspend fun prepareMysRuntime(
        settings: AppSettings,
        hasMysTasks: Boolean,
    ): MysRuntimePreparation =
        coroutineScope {
            val version = async { resolveMysAppVersion(settings, hasMysTasks) }
            val deviceId = async(Dispatchers.IO) { store.effectiveMysDeviceId(forceCreate = hasMysTasks) }
            MysRuntimePreparation(
                appVersion = version.await(),
                deviceId = deviceId.await(),
            ).also { prepared ->
                log(
                    "DEBUG",
                    "米游社运行参数已准备",
                    "deviceGenerated=${prepared.deviceId.isNotBlank()}, " +
                        "version=${prepared.appVersion.ifBlank { "<empty>" }}",
                )
            }
        }

    private suspend fun prepareCloudRuntime(
        settings: AppSettings,
        hasCloudYs: Boolean,
        hasCloudSr: Boolean,
        hasCloudTasks: Boolean,
    ): CloudRuntimePreparation =
        coroutineScope {
            val deviceId = async(Dispatchers.IO) { store.effectiveCloudDeviceId(forceCreate = hasCloudTasks) }
            val ysVersion = async {
                if (!settings.cloudVersionAutoFetch || !hasCloudYs) return@async null
                try {
                    CloudVersionRepository.refreshYs(httpTransport)
                        .onFailure { e ->
                            log("WARN", "云原神版本刷新失败，使用缓存", "${e.javaClass.simpleName}: ${e.message}")
                        }.getOrNull()
                } catch (e: Throwable) {
                    e.throwIfCancellation()
                    log("WARN", "云原神版本准备异常，继续使用缓存", "${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
            val srVersion = async {
                if (!settings.cloudVersionAutoFetch || !hasCloudSr) return@async null
                try {
                    CloudVersionRepository.refreshSr(httpTransport)
                        .onFailure { e ->
                            log("WARN", "云崩铁版本刷新失败，使用缓存", "${e.javaClass.simpleName}: ${e.message}")
                        }.getOrNull()
                } catch (e: Throwable) {
                    e.throwIfCancellation()
                    log("WARN", "云崩铁版本准备异常，继续使用缓存", "${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }

            val preparedYs = ysVersion.await()
            val preparedSr = srVersion.await()
            if (preparedYs != null || preparedSr != null) {
                settingsUpdateMutex.withLock {
                    val latest = store.getAppSettings()
                    val updated =
                        latest.copy(
                            cloudYsVersion = preparedYs ?: latest.cloudYsVersion,
                            cloudSrVersion = preparedSr ?: latest.cloudSrVersion,
                        )
                    store.saveAppSettings(updated)
                    CloudVersionRepository.applyOverrides(updated.cloudYsVersion, updated.cloudSrVersion)
                }
                preparedYs?.let { log("INFO", "云原神版本已准备", "version=$it") }
                preparedSr?.let { log("INFO", "云崩铁版本已准备", "version=$it") }
            }

            CloudRuntimePreparation(deviceId = deviceId.await()).also { prepared ->
                log(
                    "DEBUG",
                    "云游戏运行参数已准备",
                    "deviceGenerated=${prepared.deviceId.isNotBlank()}, forceCreate=$hasCloudTasks",
                )
            }
        }

    private suspend fun resolveMysAppVersion(
        settings: AppSettings,
        hasMysTasks: Boolean,
    ): String {
        val configured = settings.mysAppVersion.trim()
        val current = MysAppVersionRepository.effectiveVersion(configured)
        if (!settings.mysAppVersionAutoFetch || !hasMysTasks) return configured

        log("INFO", "米游社版本自动校验已开启，正在获取最新版本…", "current=$current")
        val before = MysAppVersionRepository.currentVersion
        val result = withContext(Dispatchers.IO) { MysAppVersionRepository.refreshIfNeeded(httpTransport) }
        return result.fold(
            onSuccess = { latest ->
                settingsUpdateMutex.withLock {
                    val currentSettings = store.getAppSettings()
                    store.saveAppSettings(currentSettings.copy(mysAppVersion = latest))
                }
                if (latest == before) {
                    log("OK", "米游社版本校验通过：当前已是最新版本 $latest")
                } else {
                    log(
                        "INFO",
                        "米游社版本校验：发现最新版本 $latest，本次签到将优先使用最新版本",
                        "previous=$before, latest=$latest",
                    )
                }
                latest
            },
            onFailure = { e ->
                log(
                    "WARN",
                    "米游社版本自动获取失败，将使用" +
                        "${if (configured.isBlank()) "内置默认/缓存" else "自定义"}版本 $current",
                    "${e.javaClass.simpleName}: ${e.message}",
                )
                current
            },
        )
    }

    private fun validateCookie(
        label: String,
        cookie: String,
    ) {
        val required = listOf("cookie_token", "account_id")
        val lower = cookie.lowercase()
        val missing = required.filter { !lower.contains(it) }
        val hasLtoken = lower.contains("ltoken")
        val hasStoken = lower.contains("stoken")

        if (missing.isNotEmpty()) {
            log(
                "WARN",
                "[$label] Cookie 缺少必要字段: ${missing.joinToString(", ")}",
                "cookieLength=${cookie.length}, hasLtoken=$hasLtoken, hasStoken=$hasStoken, " +
                    "fieldsFound=${required.filter { lower.contains(it) }.joinToString()}",
            )
        } else if (cookie.length < 50) {
            log(
                "WARN",
                "[$label] Cookie 过短（${cookie.length} 字符），可能不完整",
                "hasLtoken=$hasLtoken, hasStoken=$hasStoken",
            )
        } else {
            log(
                "INFO",
                "[$label] Cookie 校验通过",
                "cookieLength=${cookie.length}, hasLtoken=$hasLtoken, hasStoken=$hasStoken",
            )
        }
    }

    private fun logAccountProcessingStart(acc: Account) {
        log(
            "INFO",
            "────── 开始处理账号: ${acc.label} ──────",
            "accountId=${acc.id.takeLast(8)}, thread=${Thread.currentThread().name}, " +
                "enabled=${acc.enabled}, hasMysCookie=${acc.mysCookie.isNotBlank()}, hasMysKeepLogin=${acc.hasMysKeepLogin}, " +
                "qrLoginBound=${acc.qrLoginBound}, hasCloudYS=${hasCloudYS(acc)}, hasCloudSR=${hasCloudSR(acc)}, " +
                "selectedMysGames=${acc.selectedMysGames().joinToString { it.key }}, " +
                "selectedCloudGames=${acc.selectedCloudBindings(cloudGameBindings).joinToString { it.game.key }}",
        )
    }

    private fun sortTaskResultsForRecord(rawResults: List<TaskResult>): List<TaskResult> {
        val gameOrder = Games.ALL.mapIndexed { index, gameInfo -> gameInfo.key to index }.toMap()
        // 使用一次 sortedWith 排序完成结果归并，并显式指定泛型避免编译器推断失败。
        return rawResults.sortedWith(
            compareBy<TaskResult> { gameOrder[it.gameKey] ?: Int.MAX_VALUE }
                .thenBy { it.accountLabel }
                .thenBy { it.game },
        )
    }

    private suspend fun processAccount(
        acc: Account,
        settings: AppSettings,
        mysPreparation: Deferred<MysRuntimePreparation>,
        cloudPreparation: Deferred<CloudRuntimePreparation>,
        actIdCache: ConcurrentHashMap<String, String>,
        total: Int,
        done: AtomicInteger,
        onProgress: (String, Int, Int) -> Unit,
        shouldExecuteTask: (taskId: String) -> Boolean,
        onTaskStarted: (String, String) -> Boolean,
        onTaskCompleted: (taskId: String, result: TaskResult, progressLabel: String) -> Unit,
    ) {
        val accountStart = System.currentTimeMillis()
        logAccountProcessingStart(acc)

        val afterMys =
            if (acc.selectedMysGames().isNotEmpty() || (MysCoinCheckIn.featureEnabled && acc.mysCoinEnabled)) {
                val prepared = mysPreparation.await()
                mysSignExecutor.runAccount(
                    acc = acc,
                    settings = settings,
                    runMysAppVersion = prepared.appVersion,
                    actIdCache = actIdCache,
                    actIdCacheMutex = actIdCacheMutex,
                    mysDeviceId = prepared.deviceId,
                    total = total,
                    done = done,
                    onProgress = onProgress,
                    shouldExecuteTask = shouldExecuteTask,
                    onTaskStarted = onTaskStarted,
                    onTaskCompleted = onTaskCompleted,
                )
            } else {
                acc
            }
        if (afterMys.selectedCloudBindings(cloudGameBindings).isNotEmpty()) {
            val prepared = cloudPreparation.await()
            cloudSignExecutor.runAccount(
                acc = afterMys,
                cloudGameBindings = cloudGameBindings,
                cloudDeviceId = prepared.deviceId,
                total = total,
                done = done,
                onProgress = onProgress,
                shouldExecuteTask = shouldExecuteTask,
                onTaskStarted = onTaskStarted,
                onTaskCompleted = onTaskCompleted,
            )
        }

        val accountElapsed = System.currentTimeMillis() - accountStart
        log("INFO", "────── 账号 ${acc.label} 处理完成 (${formatSignInElapsed(accountElapsed)}) ──────")
    }


    private fun markProgressTaskRunning(
        taskId: String,
        message: String,
    ) {
        runCoordinator?.markTaskRunning(taskId, message)
    }

    private fun markProgressTaskFinished(
        taskId: String,
        result: TaskResult,
    ) {
        runCoordinator?.markTaskFinished(taskId, taskStatusFromResult(result), result.message)
    }

    private fun rootBlockResultMessage(decision: RootBlockingPolicy.Decision): String =
        if (decision == RootBlockingPolicy.Decision.BLOCK_ROOT_EVIDENCE) {
            RootBlockMessages.DetectedContent
        } else {
            RootBlockMessages.CheckFailedContent
        }

    private fun logOutcome(
        account: String,
        game: String,
        success: Boolean,
        skipped: Boolean,
        message: String,
        detail: String = "",
        elapsedMs: Long = 0,
    ) {
        val elapsed = if (elapsedMs > 0) " (${formatSignInElapsed(elapsedMs)})" else ""
        val level =
            when {
                skipped -> "WARN"
                success -> "OK"
                else -> "ERROR"
            }
        log(level, "[$account · $game] $message$elapsed", detail)
    }

    private fun addFailedResultsForAccount(
        acc: Account,
        e: Exception,
        isTaskCompleted: (taskId: String) -> Boolean,
        isTaskRunning: (taskId: String) -> Boolean,
        onTaskCompleted: (taskId: String, result: TaskResult, progressLabel: String) -> Unit,
    ) {
        val errorMsg = "账号处理异常终止：${ErrorText.fromException(e)}"
        buildFailedTaskResults(
            account = acc,
            errorMessage = errorMsg,
            cloudGameBindings = cloudGameBindings,
            failure = TaskFailureClassifier.fromException(e),
        ).forEach { result ->
            val taskId = taskIdForResult(acc, result, cloudGameBindings)
            if (!isTaskCompleted(taskId)) {
                val finalResult =
                    if (isTaskRunning(taskId)) {
                        result.asUnknownAfterRequest("account-exception-after-request")
                    } else {
                        result
                    }
                onTaskCompleted(taskId, finalResult, "${acc.label} · 异常处理")
            }
        }
    }

    private fun TaskResult.asUnknownAfterRequest(errorCode: String): TaskResult =
        copy(
            success = false,
            skipped = true,
            alreadySigned = false,
            message = RunPersistenceRepository.RESULT_UNKNOWN_MESSAGE,
            rewardName = "",
            rewardCount = "",
            rewardIcon = "",
            failureCategory = FailureCategory.RESULT_UNKNOWN,
            errorCode = errorCode,
            retryable = false,
        )

    private suspend fun finish(
        record: RunRecord,
        actIdCache: ConcurrentHashMap<String, String>,
        startTime: Long,
        enqueuePostRunActions: Boolean = true,
    ) {
        val totalElapsed = System.currentTimeMillis() - startTime
        val freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024

        log(
            "INFO",
            "签到完成：成功 ${record.succeeded}，已签 ${record.alreadySigned}，" +
                "失败 ${record.failed}，待确认 ${record.resultUnknown}，跳过 ${record.skipped}",
            "总耗时 ${formatSignInElapsed(totalElapsed)}, freeHeap=${freeMem}MB",
        )
        log("INFO", "========== 签到结束 (${formatSignInElapsed(totalElapsed)}) ==========")

        withContext(NonCancellable + Dispatchers.IO) {
            try {
                store.saveActIdCache(actIdCache.toMap())
            } catch (e: Exception) {
                log("WARN", "保存 act_id 缓存失败", "${e.javaClass.simpleName}: ${e.message}")
            }
            val postRunActions =
                if (enqueuePostRunActions) {
                    buildList {
                        try {
                            if (store.getAppSettings().notifyEnabled) {
                                add(PostRunActionRequest(PostRunActionType.NOTIFICATION))
                            }
                        } catch (e: Exception) {
                            log("WARN", "读取通知设置失败，本次不创建通知待办", e.javaClass.simpleName)
                        }
                        try {
                            if (record.total > 0 && store.getMailSettings().enabled) {
                                add(PostRunActionRequest(PostRunActionType.EMAIL))
                            }
                        } catch (e: Exception) {
                            log("WARN", "读取邮件设置失败，本次不创建邮件待办", e.javaClass.simpleName)
                        }
                    }
                } else {
                    emptyList()
                }
            val currentRunLogs = logs.toList().sortedBy { it.timestamp }
            runPersistenceRepository.finishRun(record, currentRunLogs, postRunActions)
        }
        try {
            postRunActionScheduler.scheduleForRun(record.runId)
        } catch (e: Exception) {
            e.throwIfCancellation()
            log("WARN", "运行结果已保存，投递任务将在下次启动恢复", e.javaClass.simpleName)
        }
        currentCoroutineContext().ensureActive()
        // 数据已经原子提交；以下仅刷新观察流，失败不会回滚已提交结果。
        try {
            historyRepository.reload()
            signInCalendarRepository.reload()
        } catch (e: Exception) {
            e.throwIfCancellation()
            log("WARN", "刷新签到结果视图失败", "${e.javaClass.simpleName}: ${e.message}")
        }
        currentCoroutineContext().ensureActive()
    }
}
