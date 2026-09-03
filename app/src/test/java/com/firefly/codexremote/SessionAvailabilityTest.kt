package com.firefly.codexremote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAvailabilityTest {
    @Test
    fun onlyExactResumableValuesCanBeImported() {
        assertTrue(isResumableSessionAvailability("RESUMABLE"))
        assertTrue(isResumableSessionAvailability("SESSION_AVAILABILITY_RESUMABLE"))
        assertFalse(isResumableSessionAvailability("NOT_RESUMABLE"))
        assertFalse(isResumableSessionAvailability("SESSION_AVAILABILITY_NOT_RESUMABLE"))
        assertFalse(isResumableSessionAvailability("AVAILABLE"))
    }

    @Test
    fun everyUnavailableStateHasAChineseDescription() {
        assertEquals(
            "可能正在其他客户端使用，暂不可导入",
            sessionAvailabilityDescription("POSSIBLY_LIVE_ELSEWHERE"),
        )
        assertEquals("无法继续此会话", sessionAvailabilityDescription("SESSION_AVAILABILITY_NOT_RESUMABLE"))
        assertEquals("未知状态，暂不可导入", sessionAvailabilityDescription("FUTURE_VALUE"))
        assertEquals("状态未知，暂不可导入", sessionAvailabilityDescription(""))
    }

    @Test
    fun sleepingCodexNeverShowsExpiringNotice() {
        val warning = ProtocolWarning(code = "MANAGEMENT_EXPIRING_SOON")
        assertEquals(
            emptyList<String>(),
            codexUiProtocolNotices(
                CodexSummary("C", "", "", "UNAVAILABLE", managementState = "UNMANAGED", warnings = listOf(warning)),
            ),
        )
        assertEquals(
            listOf("此会话即将休眠"),
            codexUiProtocolNotices(CodexSummary("C", "", "", "IDLE", warnings = listOf(warning))),
        )
    }
}
