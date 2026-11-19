@file:Suppress(
    "detekt:InjectDispatcher",
    "detekt:TooGenericExceptionCaught",
)

package com.questtick.sign

/*
 * 运行前的参数准备：米游社 / 云游戏的设备 ID 与版本号（含自动获取与设置回写）。
 * 失败一律降级为缓存值，不阻断签到。
 */
import com.questtick.core.throwIfCancellation
import com.questtick.data.AppSettings
import com.questtick.data.SecureStore
import com.questtick.net.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class MysRuntimePreparation(
    val appVersion: String,
    val deviceId: String,
)

internal data class CloudRuntimePreparation(
    val deviceId: String,
)

/** 一次运行所需的设备 ID 与版本号准备。 */
internal class RunRuntimePreparation(
    private val store: SecureStore,
    private val httpTransport: HttpTransport,
    private val log: (level: String, message: String, detail: String) -> Unit,
) {
    // 米游社与云游戏版本并行刷新时，整份设置必须串行合并，避免后写入的一方覆盖另一方。
    private val settingsUpdateMutex = Mutex()

    suspend fun prepareMys(
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

    @Suppress("detekt:LongMethod")
    suspend fun prepareCloud(
        settings: AppSettings,
        hasCloudYs: Boolean,
        hasCloudSr: Boolean,
        hasCloudTasks: Boolean,
    ): CloudRuntimePreparation =
        coroutineScope {
            val deviceId = async(Dispatchers.IO) { store.effectiveCloudDeviceId(forceCreate = hasCloudTasks) }
            val ysVersion =
                async {
                    if (!settings.cloudVersionAutoFetch || !hasCloudYs) return@async null
                    try {
                        CloudVersionRepository
                            .refreshYs(httpTransport)
                            .onFailure { e ->
                                log("WARN", "云原神版本刷新失败，使用缓存", "${e.javaClass.simpleName}: ${e.message}")
                            }.getOrNull()
                    } catch (e: Throwable) {
                        e.throwIfCancellation()
                        log("WARN", "云原神版本准备异常，继续使用缓存", "${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                }
            val srVersion =
                async {
                    if (!settings.cloudVersionAutoFetch || !hasCloudSr) return@async null
                    try {
                        CloudVersionRepository
                            .refreshSr(httpTransport)
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
                    CloudVersionRepository.applyOverrides(
                        updated.cloudYsVersion,
                        updated.cloudSrVersion,
                        updated.cloudZzzVersion,
                    )
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
                    log("OK", "米游社版本校验通过：当前已是最新版本 $latest", "")
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
}
