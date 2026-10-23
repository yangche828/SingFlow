package com.singflow.android.core.subscription

/**
 * 订阅自动更新 Worker 的一个执行回合的汇总。
 *
 * - [EMPTY]：没有任何符合条件的订阅（成功收工）；
 * - [ANY_SUCCESS]：至少一个订阅刷新成功（成功收工）；
 * - [ALL_FAILED]：所有订阅刷新都失败（按退避重试）；
 * - [THREW]：刷新流程抛出未捕获异常（视为全部失败，按退避重试）。
 */
enum class SubscriptionRunOutcome {
    EMPTY,
    ANY_SUCCESS,
    ALL_FAILED,
    THREW
}

/**
 * Worker 单次执行后的调度动作。
 */
enum class SubscriptionWorkerAction {
    SUCCESS,
    RETRY,
    FAILURE
}

/**
 * 订阅 Worker 重试决策（纯函数，无 Android 依赖，可直接 JUnit 验证）。
 *
 * 规则对应 `SubscriptionUpdateWorker`：
 * - 已达 [MAX_ATTEMPTS]（含首次）后不再进入执行体（防死循环兜底）；
 * - 全部失败或抛异常时按指数退避重试；
 * - 重试次数用尽的当次直接返回 [SubscriptionWorkerAction.FAILURE]，
 *   避免无限重试耗电。
 */
object SubscriptionRetryPolicy {

    /** 最大尝试次数（含首次）。 */
    const val MAX_ATTEMPTS = 5

    /**
     * 计算一次执行后的调度动作。
     *
     * @param runAttemptCount WorkManager 记录的重试次数（从 0 起）
     * @param outcome 本回合执行结果汇总
     */
    fun decide(runAttemptCount: Int, outcome: SubscriptionRunOutcome): SubscriptionWorkerAction {
        if (runAttemptCount >= MAX_ATTEMPTS) return SubscriptionWorkerAction.FAILURE
        return when (outcome) {
            SubscriptionRunOutcome.EMPTY,
            SubscriptionRunOutcome.ANY_SUCCESS -> SubscriptionWorkerAction.SUCCESS

            SubscriptionRunOutcome.ALL_FAILED,
            SubscriptionRunOutcome.THREW ->
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                    SubscriptionWorkerAction.FAILURE
                } else {
                    SubscriptionWorkerAction.RETRY
                }
        }
    }
}
