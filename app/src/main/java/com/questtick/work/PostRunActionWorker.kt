package com.questtick.work

/** 独立执行签到完成后的通知和邮件投递任务。 */

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.questtick.data.RunRecord
import com.questtick.data.SecureStore
import com.questtick.i18n.AppLocaleController
import com.questtick.mail.Mailer
import com.questtick.notify.Notifier
import com.questtick.repository.run.PostRunActionRepository
import com.questtick.repository.run.PostRunActionType
import com.questtick.repository.log.AppErrorLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@HiltWorker
class PostRunActionWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val store: SecureStore,
        private val repository: PostRunActionRepository,
        private val scheduler: PostRunActionScheduler,
        private val appErrorLogger: AppErrorLogger,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val actionId = inputData.getString(KEY_ACTION_ID)?.takeIf { it.isNotBlank() }
            if (actionId == null) {
                appErrorLogger.record("运行后动作投递", IllegalArgumentException("缺少 post_run_action_id"))
                return Result.failure()
            }
            return try {
                when (val claim = repository.claim(actionId)) {
                    is PostRunActionRepository.ClaimResult.Claimed -> deliver(claim.action)
                    is PostRunActionRepository.ClaimResult.NotReady -> Result.retry()
                    PostRunActionRepository.ClaimResult.Missing,
                    PostRunActionRepository.ClaimResult.Terminal,
                    -> Result.success()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appErrorLogger.record("运行后动作领取", e)
                Result.retry()
            }
        }

        private suspend fun deliver(action: com.questtick.data.db.PostRunActionEntity): Result {
            return try {
                val record = RunRecord.fromJson(JSONObject(action.payloadJson))
                when (PostRunActionType.valueOf(action.type)) {
                    PostRunActionType.NOTIFICATION -> deliverNotification(action.actionId, record)
                    PostRunActionType.EMAIL -> deliverEmail(action, record)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                appErrorLogger.record("运行后动作解析", e)
                finishWithoutRetry(action, "unsupported-action-type")
            } catch (e: org.json.JSONException) {
                appErrorLogger.record("运行后动作解析", e)
                finishWithoutRetry(action, "invalid-action-payload")
            } catch (e: Exception) {
                appErrorLogger.record("运行后动作投递", e)
                retryOrFinish(action, e.javaClass.simpleName.ifBlank { "delivery-error" })
            }
        }

        private fun deliverNotification(
            actionId: String,
            record: RunRecord,
        ): Result {
            if (!store.getAppSettings().notifyEnabled) {
                repository.markCancelled(actionId, "notification-disabled")
                return Result.success()
            }
            return if (Notifier.showResult(applicationContext, record)) {
                repository.markDelivered(actionId)
                Result.success()
            } else {
                repository.markCancelled(actionId, "notification-permission-unavailable")
                Result.success()
            }
        }

        private suspend fun deliverEmail(
            action: com.questtick.data.db.PostRunActionEntity,
            record: RunRecord,
        ): Result {
            val settings = store.getMailSettings()
            if (!settings.enabled) {
                repository.markCancelled(action.actionId, "mail-disabled")
                return Result.success()
            }
            if (
                settings.mailTo.isBlank() || settings.smtpServer.isBlank() ||
                settings.username.isBlank() || settings.password.isBlank()
            ) {
                repository.markCancelled(action.actionId, "mail-configuration-incomplete")
                return Result.success()
            }
            val result = Mailer.send(
                settings,
                record,
                AppLocaleController.resolved(applicationContext),
            )
            return result.fold(
                onSuccess = {
                    repository.markDelivered(action.actionId)
                    Result.success()
                },
                onFailure = { error ->
                    appErrorLogger.record("签到结果邮件投递", error)
                    retryOrFinish(action, error.javaClass.simpleName.ifBlank { "mail-error" })
                },
            )
        }

        private fun finishWithoutRetry(
            action: com.questtick.data.db.PostRunActionEntity,
            errorCode: String,
        ): Result {
            repository.markDeliveryFailure(
                action = action,
                errorCategory = PostRunActionRepository.ERROR_CATEGORY_CONFIGURATION,
                errorCode = errorCode,
                retryable = false,
            )
            return Result.success()
        }

        private fun retryOrFinish(
            action: com.questtick.data.db.PostRunActionEntity,
            errorCode: String,
        ): Result {
            val updated =
                repository.markDeliveryFailure(
                    action = action,
                    errorCategory = PostRunActionRepository.ERROR_CATEGORY_DELIVERY,
                    errorCode = errorCode,
                    retryable = true,
                )
            if (updated.status == PostRunActionRepository.STATUS_PENDING) scheduler.schedule(updated)
            return Result.success()
        }

        companion object {
            const val KEY_ACTION_ID = "post_run_action_id"
        }
    }
