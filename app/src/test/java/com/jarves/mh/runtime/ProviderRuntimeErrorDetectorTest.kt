package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRuntimeErrorDetectorTest {
    @Test
    fun apiRetryIsNeverFatalDuringStream() {
        for (status in listOf(401, 403, 429, 500)) {
            val event =
                """{"type":"system","subtype":"api_retry","attempt":1,"error_status":$status,"error":"authentication_failed"}"""
            assertNull(ProviderRuntimeErrorDetector.detect(event))
            assertNotNull(ProviderRuntimeErrorDetector.streamNotice(event))
        }
    }

    @Test
    fun midStreamHttp401LineIsNotFatal() {
        assertNull(ProviderRuntimeErrorDetector.detect("API Error: HTTP 401 unauthorized"))
        assertNull(ProviderRuntimeErrorDetector.detect("authentication_failed"))
        assertNull(ProviderRuntimeErrorDetector.detect("invalid api key"))
    }

    @Test
    fun assistantTextMentioningRateLimitIsNotAProviderNotice() {
        val assistant =
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Implement rate limit middleware"}]}}"""
        assertNull(ProviderRuntimeErrorDetector.streamNotice(assistant))
        assertNull(ProviderRuntimeErrorDetector.streamNotice("HTTP 429 rate limit exceeded"))
    }

    @Test
    fun expiredSubstringIsNotFatal() {
        assertNull(ProviderRuntimeErrorDetector.detect("prompt cache expired"))
        assertEquals("prompt cache expired", ProviderRuntimeErrorDetector.classifyFinal("prompt cache expired"))
    }

    @Test
    fun ordinaryRuntimeOutputIsNotFatal() {
        assertNull(ProviderRuntimeErrorDetector.detect("Claude Code connected"))
        assertNull(ProviderRuntimeErrorDetector.streamNotice("Claude Code connected"))
    }

    @Test
    fun finalResultErrorWithAuthIsRejectedKey() {
        val event =
            """{"type":"result","subtype":"error","is_error":true,"result":"API Error: 401 authentication_failed"}"""
        assertEquals(
            ProviderRuntimeErrorDetector.REJECTED_KEY,
            ProviderRuntimeErrorDetector.detect(event),
        )
    }

    @Test
    fun finalResultErrorWithBlankResultUsesOtherFields() {
        val event =
            """{"type":"result","subtype":"error","is_error":true,"result":"","error":"authentication_failed"}"""
        assertEquals(
            ProviderRuntimeErrorDetector.REJECTED_KEY,
            ProviderRuntimeErrorDetector.detect(event),
        )
    }

    @Test
    fun finalResultErrorWith403IsDenied() {
        val event =
            """{"type":"result","subtype":"error","is_error":true,"result":"API Error: HTTP 403 Forbidden"}"""
        assertEquals(
            ProviderRuntimeErrorDetector.REQUEST_DENIED,
            ProviderRuntimeErrorDetector.detect(event),
        )
    }

    @Test
    fun classifyFinalMapsAuthRateLimitAndUserNotFound() {
        assertEquals(
            ProviderRuntimeErrorDetector.USER_NOT_FOUND,
            ProviderRuntimeErrorDetector.classifyFinal("Failed to authenticate. API Error: 401 User not found."),
        )
        assertEquals(
            ProviderRuntimeErrorDetector.REJECTED_KEY,
            ProviderRuntimeErrorDetector.classifyFinal("invalid api key"),
        )
        assertEquals(
            ProviderRuntimeErrorDetector.REJECTED_KEY,
            ProviderRuntimeErrorDetector.classifyFinal("Authentication required"),
        )
        assertEquals(
            ProviderRuntimeErrorDetector.REJECTED_KEY,
            ProviderRuntimeErrorDetector.classifyFinal("token expired"),
        )
        assertEquals(
            ProviderRuntimeErrorDetector.RATE_LIMITED,
            ProviderRuntimeErrorDetector.classifyFinal("HTTP 429 rate limit exceeded"),
        )
        assertEquals(
            ProviderRuntimeErrorDetector.REQUEST_DENIED,
            ProviderRuntimeErrorDetector.classifyFinal("HTTP 403 Forbidden"),
        )
        // Billing quota is not forced into "wait and retry"
        assertEquals(
            "quota exceeded for this API key",
            ProviderRuntimeErrorDetector.classifyFinal("quota exceeded for this API key"),
        )
    }
}
