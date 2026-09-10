package com.questtick.work

/** 将签到完成后的通知和邮件动作提交到 WorkManager。 */

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.questtick.data.db.PostRunActionEntity
import com.questtick.repository.run.PostRunActionRepository
import com.questtick.repository.run.PostRunActionType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostRunActionScheduler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val repository: PostRunActionRepository,
    ) {
        fun scheduleOutstanding(now: Long = System.currentTimeMillis()) {
            repository.outstanding().forEach { schedule(it, now) }
        }

        fun scheduleForRun(
            runId: String,
            now: Long = System.currentTimeMillis(),
        ) {
            repository.outstanding().filter { it.runId == runId }.forEach { schedule(it, now) }
        }

        fun schedule(
            action: PostRunActionEntity,
            now: Long = System.currentTimeMillis(),
        ) {
            val readyAt =
                if (action.status == PostRunActionRepository.STATUS_PROCESSING) {
                    action.leaseUntil
                } else {
                    action.nextAttemptAt
                }
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(
                        if (action.type == PostRunActionType.EMAIL.name) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED,
                    ).build()
            val request =
                OneTimeWorkRequestBuilder<PostRunActionWorker>()
                    .setInputData(Data.Builder().putString(PostRunActionWorker.KEY_ACTION_ID, action.actionId).build())
                    .setConstraints(constraints)
                    .setInitialDelay((readyAt - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30L, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(action.actionId, action.attemptCount),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private fun uniqueWorkName(
            actionId: String,
            attemptCount: Int,
        ): String = "post_run_action:$actionId:$attemptCount"
    }
