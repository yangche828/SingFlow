package com.singflow.android.core.subscription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SubscriptionRetryPolicy] 重试决策矩阵单元测试：
 * 空列表收工、部分成功收工、全失败退避重试与次数耗尽兜底。
 */
class SubscriptionRetryPolicyTest {

    @Test
    fun `无符合条件的订阅应为成功`() {
        assertEquals(
            SubscriptionWorkerAction.SUCCESS,
            SubscriptionRetryPolicy.decide(0, SubscriptionRunOutcome.EMPTY)
        )
    }

    @Test
    fun `部分成功应为成功`() {
        assertEquals(
            SubscriptionWorkerAction.SUCCESS,
            SubscriptionRetryPolicy.decide(2, SubscriptionRunOutcome.ANY_SUCCESS)
        )
    }

    @Test
    fun `全失败且次数未用尽应重试`() {
        assertEquals(
            SubscriptionWorkerAction.RETRY,
            SubscriptionRetryPolicy.decide(0, SubscriptionRunOutcome.ALL_FAILED)
        )
        assertEquals(
            SubscriptionWorkerAction.RETRY,
            SubscriptionRetryPolicy.decide(3, SubscriptionRunOutcome.ALL_FAILED)
        )
        assertEquals(
            SubscriptionWorkerAction.RETRY,
            SubscriptionRetryPolicy.decide(0, SubscriptionRunOutcome.THREW)
        )
    }

    @Test
    fun `最后一次全失败应收工为失败`() {
        assertEquals(
            SubscriptionWorkerAction.FAILURE,
            SubscriptionRetryPolicy.decide(
                SubscriptionRetryPolicy.MAX_ATTEMPTS - 1,
                SubscriptionRunOutcome.ALL_FAILED
            )
        )
        assertEquals(
            SubscriptionWorkerAction.FAILURE,
            SubscriptionRetryPolicy.decide(
                SubscriptionRetryPolicy.MAX_ATTEMPTS - 1,
                SubscriptionRunOutcome.THREW
            )
        )
    }

    @Test
    fun `超过最大次数的执行体不应被允许再重试`() {
        assertEquals(
            SubscriptionWorkerAction.FAILURE,
            SubscriptionRetryPolicy.decide(SubscriptionRetryPolicy.MAX_ATTEMPTS, SubscriptionRunOutcome.ANY_SUCCESS)
        )
    }
}
