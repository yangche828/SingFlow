package com.singflow.android.core.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅流量头解析测试。
 */
class SubscriptionUserInfoTest {

    @Test
    fun `parses full subscription userinfo header`() {
        val info = SubscriptionUserInfo.parse(
            "upload=1024; download=2048; total=4096; expire=1767225600"
        )

        assertEquals(1024L, info?.upload)
        assertEquals(2048L, info?.download)
        assertEquals(4096L, info?.total)
        assertEquals(1767225600L, info?.expireAt)
        assertEquals(3072L, info?.used)
        assertTrue(info!!.hasQuota)
        assertEquals(75, info.usedPercent)
        assertEquals(1767225600000L, info.expireAtMillis)
    }

    @Test
    fun `parses comma separated and unknown fields`() {
        val info = SubscriptionUserInfo.parse("upload=1, download=2, expire=0, foo=bar")
        assertEquals(1L, info?.upload)
        assertEquals(2L, info?.download)
        assertEquals(0L, info?.total)
        assertFalse(info!!.hasQuota)
        assertEquals(-1, info.usedPercent)
    }

    @Test
    fun `empty or invalid header returns null`() {
        assertNull(SubscriptionUserInfo.parse(null))
        assertNull(SubscriptionUserInfo.parse(""))
        assertNull(SubscriptionUserInfo.parse("foo=bar; baz=qux"))
    }

    @Test
    fun `percentage is clamped`() {
        val over = SubscriptionUserInfo(upload = 200, download = 200, total = 100)
        assertEquals(100, over.usedPercent)
    }
}
