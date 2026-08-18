package com.questtick.ui.vm

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.questtick.BuildConfig
import com.questtick.data.AppSettings
import com.questtick.data.MailSettings
import com.questtick.data.RunRecord
import com.questtick.i18n.AppLocaleController
import com.questtick.net.HttpTransport
import com.questtick.net.StreamingHttpTransport
import com.questtick.core.throwIfCancellation
import com.questtick.mail.Mailer
import com.questtick.repository.AppStateRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.repository.settings.SettingsRepository
import com.questtick.sign.AppUpdateCache
import com.questtick.sign.AppUpdateChecker
import com.questtick.sign.AppUpdateChecker.AppUpdateInfo
import com.questtick.sign.AppUpdateInstaller
import com.questtick.sign.CloudAppVersionFetcher
import com.questtick.sign.CloudVersionRepository
import com.questtick.sign.ErrorText
import com.questtick.sign.MysAppVersionFetcher
import com.questtick.sign.MysAppVersionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import okhttp3.Cache

data class AppUpdatePrompt(
    val info: AppUpdateInfo,
    val automatic: Boolean,
    val apkReady: Boolean,
)

data class AppUpdateDownloadProgress(
    val version: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val speedBytesPerSecond: Long,
)

enum class CacheCategory { REWARD_IMAGES, NETWORK_RESPONSES, APP_UPDATES, LOG_EXPORTS, OTHER_TEMP }

data class CacheStorageItem(val category: CacheCategory, val sizeBytes: Long)

data class CacheStorageState(
    val items: List<CacheStorageItem> = emptyList(),
    val loading: Boolean = true,
    val clearing: CacheCategory? = null,
    val clearingAll: Boolean = false,
) {
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appStateRepository: AppStateRepository,
        private val settingsRepository: SettingsRepository,
        private val httpTransport: HttpTransport,
        private val streamingHttpTransport: StreamingHttpTransport,
        private val networkCache: Cache,
        private val appErrorLogger: AppErrorLogger,
    ) : ViewModel() {
        private companion object {
            const val TAG = "SettingsViewModel"
            const val AUTO_CHECK_TIMEOUT_MS = 4000L
            const val MANUAL_CHECK_TIMEOUT_MS = 10000L
            const val REWARD_ICON_DIR = "reward_icons"
            const val COIL_CACHE_DIR = "coil_images"
            const val HTTP_CACHE_DIR = "http_cache"
            const val APK_UPDATE_DIR = "apk_updates"
            const val LOG_EXPORT_DIR = "exports"
            val MANAGED_CACHE_DIRS = setOf(COIL_CACHE_DIR, HTTP_CACHE_DIR, APK_UPDATE_DIR, LOG_EXPORT_DIR)
        }

        val settings: StateFlow<AppSettings> = settingsRepository.settings
        val mail: StateFlow<MailSettings> = settingsRepository.mail
        val mailLoaded: StateFlow<Boolean> = settingsRepository.mailLoaded
        fun reloadSettings() {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { settingsRepository.reloadSettings() }
            }
        }

        private val _updatePrompt = MutableStateFlow<AppUpdatePrompt?>(null)
        val updatePrompt: StateFlow<AppUpdatePrompt?> = _updatePrompt.asStateFlow()

        private val _updateDownloadProgress = MutableStateFlow<AppUpdateDownloadProgress?>(null)
        val updateDownloadProgress: StateFlow<AppUpdateDownloadProgress?> = _updateDownloadProgress.asStateFlow()

        private val _cacheStorageState = MutableStateFlow(CacheStorageState())
        val cacheStorageState: StateFlow<CacheStorageState> = _cacheStorageState.asStateFlow()

        init {
            viewModelScope.launch(Dispatchers.IO) { settingsRepository.reloadMail() }
            refreshCacheStorage()
        }

        private val manualUpdateCheckGeneration = AtomicInteger(0)

        fun saveSchedule(appSettings: AppSettings) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    settingsRepository.saveSettings(appSettings)
                } catch (e: Exception) {
                    appErrorLogger.record("设置保存", e)
                    appStateRepository.triggerToast("保存失败: ${friendlyActionError(e)}")
                }
            }
        }

        fun saveLanguage(
            appSettings: AppSettings,
            language: String,
        ) {
            if (!BuildConfig.DEBUG) return
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    settingsRepository.saveSettings(appSettings.copy(appLanguage = language))
                } catch (e: Exception) {
                    appErrorLogger.record("界面语言保存", e)
                    appStateRepository.triggerToast("保存失败: ${friendlyActionError(e)}")
                }
            }
        }

        fun saveMail(mailSettings: MailSettings) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { settingsRepository.saveMailSettings(mailSettings) }
                    .onFailure {
                        appErrorLogger.record("邮件设置保存", it)
                        appStateRepository.triggerToast("保存失败: ${friendlyActionError(it)}")
                    }
            }
        }

        fun sendTestMail(mailSettings: MailSettings) {
            viewModelScope.launch(Dispatchers.IO) {
                Mailer.send(
                    mailSettings.copy(enabled = true),
                    RunRecord(System.currentTimeMillis(), emptyList()),
                    AppLocaleController.resolved(context),
                ).onFailure {
                    appErrorLogger.record("测试邮件发送", it)
                    appStateRepository.triggerToast("发送失败: ${friendlyActionError(it)}")
                }
            }
        }

        fun refreshCacheStorage() {
            viewModelScope.launch {
                _cacheStorageState.value = _cacheStorageState.value.copy(loading = true)
                try {
                    val items = withContext(Dispatchers.IO) { scanCacheStorage() }
                    _cacheStorageState.value = _cacheStorageState.value.copy(items = items, loading = false)
                } catch (e: Exception) {
                    appErrorLogger.record("缓存扫描", e)
                    e.throwIfCancellation()
                    Log.e(TAG, "Failed to scan cache storage", e)
                    _cacheStorageState.value = _cacheStorageState.value.copy(loading = false)
                    appStateRepository.triggerToast("扫描缓存失败：${friendlyActionError(e)}")
                }
            }
        }

        fun clearCacheCategory(category: CacheCategory) {
            if (_cacheStorageState.value.clearing != null || _cacheStorageState.value.clearingAll) return
            viewModelScope.launch {
                _cacheStorageState.value = _cacheStorageState.value.copy(clearing = category)
                try {
                    withContext(Dispatchers.IO) { clearCacheCategoryOnDisk(category) }
                    val items = withContext(Dispatchers.IO) { scanCacheStorage() }
                    _cacheStorageState.value = _cacheStorageState.value.copy(items = items, clearing = null)
                    appStateRepository.triggerToast("清理成功")
                } catch (e: Exception) {
                    appErrorLogger.record("缓存分类清理", e, "清理 $category 失败")
                    e.throwIfCancellation()
                    Log.e(TAG, "Failed to clear cache category: $category", e)
                    _cacheStorageState.value = _cacheStorageState.value.copy(clearing = null)
                    appStateRepository.triggerToast("清理失败：${friendlyActionError(e)}")
                }
            }
        }

        fun clearAllCaches() {
            if (_cacheStorageState.value.clearing != null || _cacheStorageState.value.clearingAll) return
            viewModelScope.launch {
                _cacheStorageState.value = _cacheStorageState.value.copy(clearingAll = true)
                try {
                    withContext(Dispatchers.IO) { CacheCategory.entries.forEach(::clearCacheCategoryOnDisk) }
                    val items = withContext(Dispatchers.IO) { scanCacheStorage() }
                    _cacheStorageState.value = _cacheStorageState.value.copy(items = items, clearingAll = false)
                    appStateRepository.triggerToast("全部缓存已清理")
                } catch (e: Exception) {
                    appErrorLogger.record("缓存全部清理", e)
                    e.throwIfCancellation()
                    Log.e(TAG, "Failed to clear all caches", e)
                    _cacheStorageState.value = _cacheStorageState.value.copy(clearingAll = false)
                    appStateRepository.triggerToast("清理失败：${friendlyActionError(e)}")
                }
            }
        }

        fun fetchLatestMysVersion(onFinished: (String?) -> Unit) {
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { MysAppVersionFetcher.fetchLatest(httpTransport) }
                result.onSuccess { version ->
                    onFinished(version)
                }.onFailure { e ->
                    appErrorLogger.record("米游社版本手动获取", e)
                    appStateRepository.triggerToast("获取失败：${friendlyActionError(e)}")
                    onFinished(null)
                }
            }
        }

        fun fetchLatestCloudVersion(onFinished: (CloudAppVersionFetcher.CloudVersions?) -> Unit) {
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { CloudAppVersionFetcher.fetchAll(httpTransport) }
                result.onSuccess { versions ->
                    onFinished(versions)
                }.onFailure { e ->
                    appErrorLogger.record("云游戏版本手动获取", e)
                    appStateRepository.triggerToast("获取失败：${friendlyActionError(e)}")
                    onFinished(null)
                }
            }
        }

        fun checkAppUpdate(onFinished: (AppUpdateInfo?) -> Unit) {
            checkAppUpdateInternal(UpdateCheckMode.Manual, onFinished)
        }

        fun checkAppUpdateSilently(onFinished: (AppUpdateInfo?) -> Unit = {}) {
            checkAppUpdateInternal(UpdateCheckMode.StartupSilent, onFinished)
        }

        private enum class UpdateCheckMode { StartupSilent, Manual }

        private fun checkAppUpdateInternal(
            mode: UpdateCheckMode,
            onFinished: (AppUpdateInfo?) -> Unit,
        ) {
            val manualGeneration =
                if (mode == UpdateCheckMode.Manual) {
                    manualUpdateCheckGeneration.incrementAndGet()
                } else {
                    manualUpdateCheckGeneration.get()
                }
            viewModelScope.launch {
                val currentVersion = BuildConfig.VERSION_NAME
                Log.d(TAG, "checkAppUpdate start: mode=$mode, currentVersion=$currentVersion")

                val cache = readUpdateCache(currentVersion)
                val isStartupSilent = mode == UpdateCheckMode.StartupSilent
                val isFirstLaunch = cache?.info == null
                val cacheOnlyWarmUp = isStartupSilent && isFirstLaunch
                val timeoutMs = if (isStartupSilent) AUTO_CHECK_TIMEOUT_MS else MANUAL_CHECK_TIMEOUT_MS
                val cachedUpdate = cache?.info?.takeIf(::isUsableUpdateInfo)
                if (cachedUpdate != null && !cacheOnlyWarmUp && canTouchUpdatePrompt(manualGeneration)) {
                    Log.d(TAG, "cache hit has update -> prompt first")
                    showUpdatePrompt(cachedUpdate, automatic = isStartupSilent)
                    onFinished(cachedUpdate)
                    refreshCachedUpdatePrompt(
                        currentVersion = currentVersion,
                        cachedInfo = cachedUpdate,
                        automatic = isStartupSilent,
                        timeoutMs = timeoutMs,
                        manualGeneration = manualGeneration,
                    )
                    return@launch
                }

                try {
                    Log.d(TAG, "fresh update check start")
                    val info =
                        withContext(Dispatchers.IO) {
                            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                                AppUpdateChecker.checkFresh(context, currentVersion, httpTransport)
                            } ?: throw SocketTimeoutException("update check timeout")
                        }
                    Log.d(TAG, "fresh update check done: latestVersion=${info.latestVersion}, hasUpdate=${info.hasUpdate}")
                    val canTouchPrompt = canTouchUpdatePrompt(manualGeneration)

                    if (info.hasUpdate && !cacheOnlyWarmUp && canTouchPrompt) {
                        showUpdatePrompt(info, automatic = isStartupSilent)
                    } else if (!isStartupSilent) {
                        appStateRepository.triggerToast("当前已是最新版本")
                    }
                    onFinished(info)
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    appErrorLogger.record("应用更新检查", e, "检查更新失败")
                    Log.w(TAG, "fresh update check failed: mode=$mode", e)
                    val canTouchPrompt = canTouchUpdatePrompt(manualGeneration)

                    val fallback = cache?.info?.takeIf(::isUsableUpdateInfo)
                    if (isStartupSilent && fallback != null && canTouchPrompt) {
                        Log.d(TAG, "startup fallback cache has update -> prompt")
                        showUpdatePrompt(fallback, automatic = true)
                        onFinished(fallback)
                        return@launch
                    }

                    if (!isStartupSilent) {
                        appStateRepository.triggerToast("检查更新失败：${friendlyActionError(e)}")
                    }
                    onFinished(null)
                }
            }
        }

        private fun canTouchUpdatePrompt(manualGeneration: Int): Boolean =
            manualGeneration == manualUpdateCheckGeneration.get()

        private fun isUsableUpdateInfo(info: AppUpdateInfo): Boolean =
            info.hasUpdate &&
                info.latestVersion.isNotBlank() &&
                info.apkUrl.isNotBlank() &&
                (BuildConfig.DEBUG || Regex("""^[a-fA-F0-9]{64}$""").matches(info.apkSha256))

        private fun refreshCachedUpdatePrompt(
            currentVersion: String,
            cachedInfo: AppUpdateInfo,
            automatic: Boolean,
            timeoutMs: Long,
            manualGeneration: Int,
        ) {
            viewModelScope.launch {
                try {
                    val fresh =
                        withContext(Dispatchers.IO) {
                            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                                AppUpdateChecker.checkFresh(context, currentVersion, httpTransport)
                            } ?: throw SocketTimeoutException("update check timeout")
                        }
                    if (!canTouchUpdatePrompt(manualGeneration)) return@launch
                    when {
                        fresh.hasUpdate && fresh != cachedInfo -> showUpdatePrompt(fresh, automatic = automatic)
                        !fresh.hasUpdate && _updatePrompt.value?.info == cachedInfo -> _updatePrompt.value = null
                    }
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    Log.w(TAG, "refresh cached update prompt failed", e)
                }
            }
        }

        private suspend fun readUpdateCache(currentVersion: String): AppUpdateCache.CacheResult? =
            withContext(Dispatchers.IO) {
                try {
                    AppUpdateCache.getFast(context, currentVersion).also {
                        Log.d(
                            TAG,
                            buildString {
                                append("cache: ")
                                append("info=${it.info?.latestVersion}, ")
                                append("hasUpdate=${it.info?.hasUpdate}, ")
                                append("isStrong=${it.isStrong}, ")
                                append("isStale=${it.isStale}")
                            },
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "cache read failed", e)
                    null
                }
            }

        private suspend fun showUpdatePrompt(
            info: AppUpdateInfo,
            automatic: Boolean,
        ) {
            val apkReady =
                withContext(Dispatchers.IO) {
                    AppUpdateInstaller.cachedApk(context, info) != null
                }
            _updatePrompt.value = AppUpdatePrompt(info = info, automatic = automatic, apkReady = apkReady)
            if (!apkReady) {
                viewModelScope.launch(Dispatchers.IO) {
                    AppUpdateInstaller.prepareDownloadLinks(context, info, streamingHttpTransport)
                }
            }
        }

        fun dismissUpdatePrompt() {
            _updatePrompt.value = null
        }

        fun disableAutoUpdateFromPrompt() {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    settingsRepository.saveSettings(settings.value.copy(appUpdateAutoCheck = false))
                    _updatePrompt.value = null
                } catch (e: Exception) {
                    appErrorLogger.record("自动更新设置保存", e)
                    appStateRepository.triggerToast("保存失败: ${friendlyActionError(e)}")
                }
            }
        }

        fun startUpdateFromPrompt(info: AppUpdateInfo) {
            _updatePrompt.value = null
            viewModelScope.launch {
                installUpdate(info)
            }
        }

        private suspend fun installUpdate(info: AppUpdateInfo) {
            if (info.apkUrl.isBlank()) {
                appStateRepository.triggerToast("未找到 APK 下载地址")
                return
            }
            val cachedApk =
                withContext(Dispatchers.IO) {
                    AppUpdateInstaller.cachedApk(context, info)
                }
            if (cachedApk != null) {
                AppUpdateInstaller.openInstaller(context, cachedApk)
                    .onFailure {
                        appErrorLogger.record("应用安装器", it, "打开安装器失败")
                        appStateRepository.triggerToast("打开安装器失败：${friendlyActionError(it)}")
                    }
                return
            }

            var lastEmitNanos = 0L
            var downloadStartNanos = 0L
            _updateDownloadProgress.value =
                AppUpdateDownloadProgress(
                    version = info.latestVersion,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    percent = 0,
                    speedBytesPerSecond = 0L,
                )
            val downloadResult =
                AppUpdateInstaller.downloadApk(context, info, streamingHttpTransport) { downloaded, total ->
                    val now = System.nanoTime()
                    if (downloadStartNanos == 0L) downloadStartNanos = now
                    val safeTotal = total.coerceAtLeast(0L)
                    val finished = safeTotal > 0L && downloaded >= safeTotal
                    if (finished || now - lastEmitNanos >= 250_000_000L) {
                        lastEmitNanos = now
                        val elapsedNanos = (now - downloadStartNanos).coerceAtLeast(1L)
                        val speed = (downloaded * 1_000_000_000L / elapsedNanos).coerceAtLeast(0L)
                        val percent = if (safeTotal > 0L) ((downloaded * 100L) / safeTotal).toInt().coerceIn(0, 100) else 0
                        _updateDownloadProgress.value =
                            AppUpdateDownloadProgress(
                                version = info.latestVersion,
                                downloadedBytes = downloaded,
                                totalBytes = safeTotal,
                                percent = percent,
                                speedBytesPerSecond = speed,
                            )
                    }
                }
            downloadResult.onSuccess { apk ->
                _updateDownloadProgress.value = null
                AppUpdateInstaller.openInstaller(context, apk)
                    .onFailure {
                        appErrorLogger.record("应用安装器", it, "打开安装器失败")
                        appStateRepository.triggerToast("打开安装器失败：${friendlyActionError(it)}")
                    }
            }.onFailure { e ->
                _updateDownloadProgress.value = null
                appErrorLogger.record("应用更新下载", e, "下载更新失败")
                appStateRepository.triggerToast("下载更新失败：${friendlyActionError(e)}")
            }
        }

        private fun scanCacheStorage(): List<CacheStorageItem> {
            val imageLoader = SingletonImageLoader.get(context)
            return CacheCategory.entries.map { category ->
                val size = when (category) {
                    CacheCategory.REWARD_IMAGES ->
                        directorySize(File(context.filesDir, REWARD_ICON_DIR)) +
                            (imageLoader.diskCache?.size ?: directorySize(File(context.cacheDir, COIL_CACHE_DIR)))
                    CacheCategory.NETWORK_RESPONSES -> networkCache.size()
                    CacheCategory.APP_UPDATES -> directorySize(File(context.cacheDir, APK_UPDATE_DIR))
                    CacheCategory.LOG_EXPORTS -> directorySize(File(context.cacheDir, LOG_EXPORT_DIR))
                    CacheCategory.OTHER_TEMP -> otherCacheTargets().sumOf(::directorySize)
                }
                CacheStorageItem(category, size)
            }
        }

        private fun clearCacheCategoryOnDisk(category: CacheCategory) {
            when (category) {
                CacheCategory.REWARD_IMAGES -> {
                    clearDirectory(File(context.filesDir, REWARD_ICON_DIR))
                    SingletonImageLoader.get(context).run {
                        memoryCache?.clear()
                        diskCache?.clear()
                    }
                }
                CacheCategory.NETWORK_RESPONSES -> networkCache.evictAll()
                CacheCategory.APP_UPDATES -> {
                    clearDirectory(File(context.cacheDir, APK_UPDATE_DIR))
                    AppUpdateCache.clear(context)
                }
                CacheCategory.LOG_EXPORTS -> clearDirectory(File(context.cacheDir, LOG_EXPORT_DIR))
                CacheCategory.OTHER_TEMP -> otherCacheTargets().forEach(::clearTarget)
            }
        }

        private fun otherCacheTargets(): List<File> = buildList {
            context.cacheDir.listFiles()
                ?.filterNot { it.name in MANAGED_CACHE_DIRS }
                ?.let(::addAll)
            context.externalCacheDir?.let(::add)
        }

        private fun directorySize(file: File): Long {
            if (!file.exists()) return 0L
            if (file.isFile) return file.length()
            return file.listFiles()?.sumOf { directorySize(it) } ?: 0L
        }

        private fun clearDirectory(file: File) {
            if (!file.exists() || !file.isDirectory) return
            file.listFiles()?.forEach { child ->
                if (!child.deleteRecursively() && child.exists()) {
                    throw IOException("无法删除缓存：${child.absolutePath}")
                }
            }
        }

        private fun clearTarget(file: File) {
            if (!file.exists()) return
            if (file.isDirectory) clearDirectory(file)
            else if (!file.delete() && file.exists()) throw IOException("无法删除缓存：${file.absolutePath}")
        }

        private fun friendlyActionError(e: Throwable?): String =
            when {
                e == null -> "未知错误"
                e is IllegalStateException && !e.message.isNullOrBlank() -> e.message.orEmpty()
                else -> ErrorText.fromException(e)
            }

    }
