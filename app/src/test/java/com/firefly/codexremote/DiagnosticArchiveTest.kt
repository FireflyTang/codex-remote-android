package com.firefly.codexremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class DiagnosticArchiveTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun redactsCommonSecretShapesWithoutDroppingUsefulContext() {
        val input = "dial failed authKey=abc123 tskey-auth-secret Bearer ey.a-b token: visible-no password='hello'"
        val redacted = DiagnosticRedactor.redact(input)

        assertTrue(redacted.startsWith("dial failed"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("tskey-auth-secret"))
        assertFalse(redacted.contains("ey.a-b"))
        assertFalse(redacted.contains("visible-no"))
        assertFalse(redacted.contains("hello"))
        assertEquals(5, Regex("\\[REDACTED]").findAll(redacted).count())
    }

    @Test
    fun redactsTailscaleLoginUrlsAuthorizationAndQuotedSecretsWithSpaces() {
        val input = """
            login=https://login.tailscale.com/a/abcDEF_123?next=private
            Authorization: Basic dXNlcjpwYXNz
            Authorization: Bearer header.payload.signature
            authKey="a secret with spaces"
            password='another secret value'
            token=" final token "
        """.trimIndent()

        val redacted = DiagnosticRedactor.redact(input)

        assertFalse(redacted.contains("login.tailscale.com/a/"))
        assertFalse(redacted.contains("abcDEF_123"))
        assertFalse(redacted.contains("dXNlcjpwYXNz"))
        assertFalse(redacted.contains("header.payload.signature"))
        assertFalse(redacted.contains("a secret with spaces"))
        assertFalse(redacted.contains("another secret value"))
        assertFalse(redacted.contains("final token"))
        assertTrue(redacted.contains("[REDACTED_TAILSCALE_LOGIN_URL]"))
        assertTrue(redacted.contains("Authorization: Basic [REDACTED]"))
        assertTrue(redacted.contains("Authorization: Bearer [REDACTED]"))
    }

    @Test
    fun takesOnlyTailWithinLimit() {
        assertArrayEquals("6789".toByteArray(), tailBytes("0123456789".toByteArray(), 4))
        assertArrayEquals("abc".toByteArray(), tailBytes("abc".toByteArray(), 4))
        assertArrayEquals(ByteArray(0), tailBytes("abc".toByteArray(), 0))
    }

    @Test
    fun readsAtMostRequestedTailDirectlyFromFile() {
        val source = temporary.newFile("large.log").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }

        val result = readTail(source, 1024)

        assertEquals(1024, result.size)
        assertArrayEquals(source.readBytes().takeLast(1024).toByteArray(), result)
    }

    @Test
    fun selectsOnlyTailscaledTextLogsAndExcludesState() {
        val directory = temporary.newFolder("tailnet")
        val first = File(directory, "tailscaled.log1.txt").apply { writeText("one"); setLastModified(300) }
        val second = File(directory, "tailscaled.log").apply { writeText("two"); setLastModified(200) }
        File(directory, "tailscaled.log0.txt").apply { writeText("old"); setLastModified(100) }
        File(directory, "tailscaled.state").writeText("secret")
        File(directory, "auth-key.txt").writeText("secret")

        assertEquals(listOf(second.name, first.name), eligibleTailscaleLogFiles(directory.listFiles()!!.toList()).map { it.name })
    }

    @Test
    fun writesExpectedZipManifestAndRedactedLogs() {
        val output = temporary.newFile("diagnostics.zip")
        writeDiagnosticZip(
            output,
            listOf(
                diagnosticTextEntry("manifest.json", "{\"appVersion\":\"0.2.0\"}"),
                diagnosticTextEntry("app.log", "failed password=hunter2"),
                diagnosticTextEntry("tailscale/tailscaled.log1.txt", "using tskey-auth-secret"),
            ),
        )

        ZipFile(output).use { zip ->
            assertEquals(
                setOf("manifest.json", "app.log", "tailscale/tailscaled.log1.txt"),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
            val appLog = zip.getInputStream(zip.getEntry("app.log")).bufferedReader().readText()
            val tailscale = zip.getInputStream(zip.getEntry("tailscale/tailscaled.log1.txt")).bufferedReader().readText()
            assertFalse(appLog.contains("hunter2"))
            assertFalse(tailscale.contains("tskey-auth-secret"))
        }
    }

    @Test
    fun sameMillisecondExportsUseDifferentNamesAndAtomicZips() {
        val directory = temporary.newFolder("diagnostics")
        val first = diagnosticArchiveFiles(directory, 1_788_400_000_123, "aaaaaaaa-1111")
        val second = diagnosticArchiveFiles(directory, 1_788_400_000_123, "bbbbbbbb-2222")
        val entries = listOf(diagnosticTextEntry("manifest.json", "{\"ok\":true}"))

        assertFalse(first.output.absolutePath == second.output.absolutePath)
        assertFalse(first.temporary.absolutePath == second.temporary.absolutePath)
        writeDiagnosticZipAtomically(first.output, first.temporary, entries)
        writeDiagnosticZipAtomically(second.output, second.temporary, entries)

        assertTrue(first.output.isFile)
        assertTrue(second.output.isFile)
        assertFalse(first.temporary.exists())
        assertFalse(second.temporary.exists())
        ZipFile(first.output).use { assertEquals(setOf("manifest.json"), it.entries().asSequence().map { entry -> entry.name }.toSet()) }
        ZipFile(second.output).use { assertEquals(setOf("manifest.json"), it.entries().asSequence().map { entry -> entry.name }.toSet()) }
    }

    @Test
    fun failedAtomicExportRemovesTemporaryFile() {
        val directory = temporary.newFolder("failed-export")
        val files = diagnosticArchiveFiles(directory, 1_788_400_000_123, "failure")

        runCatching {
            writeDiagnosticZipAtomically(
                files.output,
                files.temporary,
                listOf(DiagnosticArchiveEntry("../invalid", byteArrayOf(1))),
            )
        }.onSuccess { throw AssertionError("invalid entry should fail") }

        assertFalse(files.output.exists())
        assertFalse(files.temporary.exists())
    }

    @Test
    fun oldArchiveCleanupKeepsNewestAndNeverTouchesTemporaryFiles() {
        val directory = temporary.newFolder("cleanup")
        repeat(7) { index ->
            File(directory, "codex-remote-diagnostics-$index.zip").apply {
                writeText("$index")
                setLastModified((index + 1).toLong())
            }
        }
        val temporaryFile = File(directory, ".codex-remote-diagnostics-current.zip.id.tmp").apply { writeText("partial") }

        cleanupOldDiagnosticArchives(directory, keep = 3)

        assertEquals(
            setOf("codex-remote-diagnostics-4.zip", "codex-remote-diagnostics-5.zip", "codex-remote-diagnostics-6.zip"),
            directory.listFiles()!!.filter { it.extension == "zip" }.map { it.name }.toSet(),
        )
        assertTrue(temporaryFile.exists())
    }

    @Test
    fun rollingAppLogBoundsFileAndRedactsBeforePersisting() {
        val file = temporary.newFile("app.log")
        val log = AppDiagnosticLog(file, maxBytes = 200)
        repeat(20) { log.append("event", "index=$it authKey=secret-$it") }

        assertTrue(file.length() <= 200)
        assertFalse(file.readText().contains("secret-"))
        assertTrue(file.readText().contains("[REDACTED]"))
    }
}
