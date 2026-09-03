package com.firefly.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreErrorDiagnosticsTest {
    @Test
    fun logsErrorEntryAndChangedTextOnlyOnceEach() {
        val logger = CoreErrorLogDeduplicator()

        assertNull(logger.newLogMessage(CoreState(phase = "starting")))
        assertEquals(
            "MobileCore error: dial Host: context deadline exceeded",
            logger.newLogMessage(CoreState(phase = "error", error = "dial Host: context deadline exceeded")),
        )
        assertNull(logger.newLogMessage(CoreState(phase = "error", error = "dial Host: context deadline exceeded")))
        assertEquals(
            "MobileCore error: websocket closed",
            logger.newLogMessage(CoreState(phase = "error", error = "websocket closed")),
        )
    }

    @Test
    fun logsSameErrorAgainAfterLeavingErrorPhase() {
        val logger = CoreErrorLogDeduplicator()
        val error = CoreState(phase = "error", error = "connection refused")

        assertTrue(logger.newLogMessage(error)!!.contains("connection refused"))
        assertNull(logger.newLogMessage(CoreState(phase = "ready")))
        assertTrue(logger.newLogMessage(error)!!.contains("connection refused"))
    }

    @Test
    fun logsChangedErrorTextEvenBeforePhaseChanges() {
        val logger = CoreErrorLogDeduplicator()

        assertEquals(
            "MobileCore error: workspace request failed",
            logger.newLogMessage(CoreState(phase = "ready", error = "workspace request failed")),
        )
        assertNull(logger.newLogMessage(CoreState(phase = "ready", error = "workspace request failed")))
    }

    @Test
    fun redactsAuthKeysFromLogMessage() {
        val logger = CoreErrorLogDeduplicator()
        val message = logger.newLogMessage(
            CoreState(phase = "error", error = "login failed authKey=secret-value tskey-auth-another-secret"),
        )!!

        assertEquals("MobileCore error: login failed authKey=[REDACTED] [REDACTED]", message)
    }

    @Test
    fun clientVersionUsesInstalledVersionAndHasSafeFallback() {
        assertEquals("0.2.0", effectiveClientVersion(" 0.2.0 "))
        assertEquals("unknown", effectiveClientVersion(null))
        assertEquals("unknown", effectiveClientVersion(" "))
    }
}
