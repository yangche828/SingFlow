package com.singflow.android.core.subscription

import android.content.Context
import android.util.Log
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
        val manager = EntryPointAccessors.fromApplication(
            applicationContext,
            SubscriptionWorkerEntryPoint::class.java
        ).subscriptionManager()
        val results = runCatching { manager.refreshAll(onlyAutoUpdate = true) }
            .onFailure { Log.e(TAG, "subscription worker failed", it) }
            .getOrElse { return Result.retry() }
        results.forEach { result ->
            Log.i(TAG, "refresh ${result.subscription?.name}: ${result.success} ${result.message}")
        }
        if (results.isEmpty()) return Result.success()
        return if (results.any { it.success }) Result.success() else Result.retry()
    }

    private companion object {
        private const val TAG = "SubscriptionWorker"
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
