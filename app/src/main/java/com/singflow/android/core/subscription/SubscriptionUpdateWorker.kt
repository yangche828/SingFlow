package com.singflow.android.core.subscription

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * 订阅自动更新 Worker。
 *
 * 每 12 小时在联网条件下刷新开启自动更新的订阅；全部失败时返回 retry。
 */
class SubscriptionUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (runAttemptCount >= MAX_ATTEMPTS) {
            Log.e(TAG, "subscription worker exceeded $MAX_ATTEMPTS attempts, giving up")
            return Result.failure()
        }
        val manager = EntryPointAccessors.fromApplication(
            applicationContext,
            SubscriptionWorkerEntryPoint::class.java
        ).subscriptionManager()
        val results = runCatching { manager.refreshAll(onlyAutoUpdate = true) }
            .onFailure { Log.e(TAG, "subscription worker failed (attempt ${runAttemptCount + 1})", it) }
            .getOrElse { return nextResult() }
        results.forEach { result ->
            Log.i(TAG, "refresh ${result.subscription?.name}: ${result.success} ${result.message}")
        }
        if (results.isEmpty()) return Result.success()
        return if (results.any { it.success }) Result.success() else nextResult()
    }

    /**
     * 失败时指数退避重试；达到最大次数后返回 failure，避免无限重试耗电。
     */
    private fun nextResult(): Result {
        return if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            Log.e(TAG, "subscription worker giving up after ${runAttemptCount + 1} attempts")
            Result.failure()
        } else {
            Result.retry()
        }
    }

    private companion object {
        private const val TAG = "SubscriptionWorker"

        /** 最大尝试次数（含首次）。 */
        private const val MAX_ATTEMPTS = 5
    }
}

/**
 * Worker 的 Hilt 入口（避免引入 hilt-work 额外依赖）。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SubscriptionWorkerEntryPoint {
    /** 订阅管理器。 */
    fun subscriptionManager(): SubscriptionManager
}

/**
 * 订阅自动更新调度器。
 */
object SubscriptionWorkScheduler {

    /** 唯一任务名。 */
    const val WORK_NAME = "singflow_subscription_auto_update"

    /**
     * 注册 12 小时周期的自动更新任务（已存在时保留原任务，不重置计时）。
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // 失败按指数退避（30s 起），避免服务端异常时高频重试。
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
