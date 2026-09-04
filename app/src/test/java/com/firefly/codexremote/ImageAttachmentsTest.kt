package com.firefly.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class ImageAttachmentsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun decodesCapabilitiesResultsAndOrderedHistoryParts() {
        val bytes = "image".toByteArray()
        val hash = sha256Hex(bytes)
        val state = decodeCoreState(
            """{"revision":1,"phase":"ready","imageAttachments":{"supported":true,"maxUploadBytes":99,"supportedMimeTypes":["image/png"],"unreferencedRetentionMs":50,"codexId":"C","loading":"none","downloadResult":{"attachment":{"attachmentId":"A","filename":"p.png","mimeType":"image/png","sizeBytes":5,"sha256":"$hash","widthPixels":2,"heightPixels":3},"contentBase64":"${Base64.getEncoder().encodeToString(bytes)}"}},"conversation":{"codexId":"C","turns":[{"turnId":"T","items":[{"itemId":"U","turnId":"T","type":"user_message","userMessage":{"textParts":["前","后"],"text":"前\n后","parts":[{"type":"text","text":"前"},{"type":"image","image":{"attachmentId":"A","filename":"p.png","mimeType":"image/png","sizeBytes":5,"sha256":"$hash"}},{"type":"text","text":"后"}]}}]}]}}""",
        )
        assertEquals(99L, state.imageAttachments?.maxUploadBytes)
        assertEquals("A", state.imageAttachments?.downloadResult?.attachment?.attachmentId)
        val parts = state.conversation!!.items.single().userMessage!!.parts
        assertEquals(listOf("text", "image", "text"), parts.map { it.type })
        assertEquals("A", parts[1].image?.attachmentId)
    }

    @Test
    fun mimeSelectionNeverLabelsJpegBytesAsUnsupportedSourceMime() {
        assertEquals("image/webp", chooseOutputMime("image/webp", setOf("image/webp")))
        assertEquals("image/jpeg", chooseOutputMime("image/gif", setOf("image/gif", "image/jpeg")))
        assertNull(chooseOutputMime("image/gif", setOf("image/gif")))
    }

    @Test
    fun draftManifestRoundTripsOnlyPrivateVerifiedOriginalAndDerivative() {
        val root = temporary.newFolder()
        val draft = File(root, "image-attachments/drafts/L.png").apply { parentFile!!.mkdirs(); writeBytes("small".toByteArray()) }
        val original = File(root, "image-attachments/originals/L.png").apply { parentFile!!.mkdirs(); writeBytes("original".toByteArray()) }
        val image = DraftImageAttachment(
            "L", "C", "p.png", "image/png", draft.absolutePath, draft.length(), sha256Hex(draft.readBytes()), 2, 3,
            original.absolutePath, "image/png", original.length(), sha256Hex(original.readBytes()),
            imageDraftScope("host-one", "C"), "UPLOAD-L",
        )
        persistDraftImages(root, mapOf(image.scope to listOf(image)))
        assertEquals(image, loadDraftImages(root)[image.scope]?.single())
        draft.writeText("tampered")
        assertTrue(loadDraftImages(root).isEmpty())
    }

    @Test
    fun verifiedCacheIsScopedStableAndRejectsCorruption() {
        val root = temporary.newFolder()
        val bytes = "image".toByteArray()
        val descriptor = ImageAttachmentDescriptor("A/../unsafe", "p.png", "image/png", bytes.size.toLong(), sha256Hex(bytes), 2, 3)
        val result = ImageAttachmentDownloadResult(descriptor, Base64.getEncoder().encodeToString(bytes))
        val cached = cacheDownloadedImage(root, imageCacheScope("host-one", "C"), result)
        assertTrue(cached.canonicalPath.startsWith(File(root, "image-attachments/cache").canonicalPath))
        assertFalse(cached.path.contains("unsafe"))
        assertEquals(cached, existingCachedImage(root, imageCacheScope("host-one", "C"), descriptor))
        assertEquals(descriptor, loadCacheDescriptorIndex(root, imageCacheScope("host-one", "C"))[descriptor.attachmentId])
        assertNull(existingCachedImage(root, imageCacheScope("host-two", "C"), descriptor))
        cached.writeText("bad")
        assertNull(existingCachedImage(root, imageCacheScope("host-one", "C"), descriptor))
    }

    @Test
    fun imageOnlyDraftCanSendButPendingOrBusyCannotDoubleDispatch() {
        val image = DraftImageAttachment("L", "C", "p.png", "image/png", "/tmp/p", 1, "h", 1, 1)
        val state = AppUiState(
            openCodexId = "C",
            core = CoreState(phase = "ready", conversation = ConversationState(codexId = "C")),
        )
        assertTrue(canDispatchMessage(state, "", listOf(image)))
        assertFalse(canDispatchMessage(state.copy(imageAttachmentBusy = true), "", listOf(image)))
        listOf("refreshing", "starting_tailnet", "error").forEach { phase ->
            assertFalse(canDispatchMessage(state.copy(core = state.core.copy(phase = phase)), "", listOf(image)))
        }
        assertFalse(canDispatchMessage(
            state.copy(optimisticUserMessages = listOf(OptimisticUserMessage("CMD", "C", "", listOf(image)))),
            "",
            listOf(image),
        ))
    }

    @Test
    fun startTurnPartsPreserveTextThenSelectedImageOrderAndSupportImageOnly() {
        val first = ImageAttachmentDescriptor(attachmentId = "A")
        val second = ImageAttachmentDescriptor(attachmentId = "B")
        val mixed = buildStartTurnPayload("说明", listOf(first, second)).getJSONArray("parts")
        assertEquals(listOf("text", "image", "image"), (0 until mixed.length()).map { mixed.getJSONObject(it).getString("type") })
        assertEquals(listOf("A", "B"), (1 until mixed.length()).map { mixed.getJSONObject(it).getString("attachmentId") })
        val imageOnly = buildStartTurnPayload("", listOf(second)).getJSONArray("parts")
        assertEquals(1, imageOnly.length())
        assertEquals("B", imageOnly.getJSONObject(0).getString("attachmentId"))
        assertEquals("plain", buildStartTurnPayload("plain", emptyList()).getString("text"))
    }

    @Test
    fun sendResolutionKeepsRetryImagesOnFailureAndDoesNotClearNewerImages() {
        val sent = DraftImageAttachment("S", "C", "s.png", "image/png", "/s", 1, "h", 1, 1)
        val newer = DraftImageAttachment("N", "C", "n.png", "image/png", "/n", 1, "h", 1, 1)
        assertEquals(listOf(sent), draftImagesAfterSendResolution(emptyList(), listOf(sent), accepted = false))
        assertTrue(draftImagesAfterSendResolution(listOf(sent), listOf(sent), accepted = true).isEmpty())
        assertNull(draftPersistenceAfterSendResolution(
            SendDraftResolution("CMD", "C", "old", draftVersion = 1, accepted = true),
            latestDraftVersion = 2,
        ))
        assertEquals(listOf(newer), draftImagesAfterSendResolution(listOf(newer), listOf(sent), accepted = true))
        assertEquals(listOf(newer), draftImagesAfterSendResolution(listOf(newer), listOf(sent), accepted = false))
    }

    @Test
    fun imageDraftsFollowOpenCodexTransitionsWithoutCrossCodexLeakage() {
        val a = DraftImageAttachment("A", "A", "a.png", "image/png", "/a", 1, "h", 1, 1)
        val b = DraftImageAttachment("B", "B", "b.png", "image/png", "/b", 1, "h", 1, 1)
        val drafts = mapOf(imageDraftScope("host", "A") to listOf(a), imageDraftScope("host", "B") to listOf(b))

        assertEquals(listOf(b), draftImagesForOpenCodexTransition("host", "A", "B", listOf(a), drafts))
        assertTrue(draftImagesForOpenCodexTransition("host", "A", null, listOf(a), drafts).isEmpty())
        assertEquals(listOf(a), draftImagesForOpenCodexTransition("host", "A", "A", listOf(a), drafts))
    }

    @Test
    fun sameCodexIdOnDifferentHostsHasIndependentPersistentDrafts() {
        val root = temporary.newFolder()
        fun image(host: String, id: String): DraftImageAttachment {
            val derivative = File(root, "image-attachments/drafts/$id.png").apply { parentFile!!.mkdirs(); writeText("d-$id") }
            val original = File(root, "image-attachments/originals/$id.png").apply { parentFile!!.mkdirs(); writeText("o-$id") }
            return DraftImageAttachment(
                id, "C", "$id.png", "image/png", derivative.absolutePath, derivative.length(), sha256Hex(derivative.readBytes()), 1, 1,
                original.absolutePath, "image/png", original.length(), sha256Hex(original.readBytes()),
                imageDraftScope(host, "C"), "UPLOAD-$id",
            )
        }
        val first = image("host-one", "one")
        val second = image("host-two", "two")
        persistDraftImages(root, mapOf(first.scope to listOf(first), second.scope to listOf(second)))
        val restored = loadDraftImages(root)

        assertEquals(listOf(first), restored[imageDraftScope("host-one", "C")])
        assertEquals(listOf(second), restored[imageDraftScope("host-two", "C")])
        assertEquals(listOf(second), draftImagesForOpenCodexTransition("host-two", null, "C", emptyList(), restored))
        assertTrue(loadDraftImages(root).keys.none { it == "C" })
    }

    @Test
    fun uploadCommandIdAndAttachmentOriginalArchiveSurviveRetryAndRebuild() {
        val root = temporary.newFolder()
        val derivative = File(root, "image-attachments/drafts/L.png").apply { parentFile!!.mkdirs(); writeText("derived") }
        val original = File(root, "image-attachments/originals/L.png").apply { parentFile!!.mkdirs(); writeText("original-quality") }
        val scope = imageDraftScope("host", "C")
        val image = DraftImageAttachment(
            "L", "C", "photo.png", "image/png", derivative.absolutePath, derivative.length(), sha256Hex(derivative.readBytes()), 2, 3,
            original.absolutePath, "image/png", original.length(), sha256Hex(original.readBytes()), scope, "UPLOAD-STABLE",
        )
        assertEquals("UPLOAD-STABLE", imageUploadCommand("C", image, derivative.readBytes()).getString("id"))
        assertEquals("UPLOAD-STABLE", imageUploadCommand("C", image, derivative.readBytes()).getString("id"))
        val descriptor = ImageAttachmentDescriptor("ATTACH", "photo.png", "image/png", derivative.length(), image.sha256, 2, 3)
        val archived = archiveUploadedOriginal(root, scope, descriptor, image)

        assertTrue(original.isFile) // retry source remains until StartTurn acceptance
        assertTrue(File(archived.originalPath).isFile)
        assertEquals(archived, archivedOriginalFor(root, scope, descriptor))
        assertNull(archivedOriginalFor(root, imageDraftScope("other-host", "C"), descriptor))
    }

    @Test
    fun optimisticAndTrackerStateForSameCodexAreIsolatedByConnectedHost() {
        val h1Scope = imageDraftScope("host-one", "C")
        val h2Scope = imageDraftScope("host-two", "C")
        val overlay = OptimisticUserMessage("CMD-H1", "C", "from h1", imageScope = h1Scope)
        val h2 = AppUiState(
            hostAddress = "edited-but-not-connected",
            connectedHostAddress = "host-two",
            openCodexId = "C",
            core = CoreState(phase = "ready", conversation = ConversationState(codexId = "C")),
            optimisticUserMessages = listOf(overlay),
        )
        assertTrue(canDispatchMessage(h2, "send on h2"))
        assertTrue(projectOptimisticUserMessages(h2.core, listOf(overlay), h2Scope).conversation!!.items.isEmpty())

        val tracker = SendDraftTracker().apply {
            track("CMD-H1", "C", "old", 1, imageScope = h1Scope)
        }
        assertNull(tracker.onCoreState(CoreState(commandId = "CMD-H1", phase = "error", error = "a turn is already running"), h2Scope))
        assertFalse(tracker.onCoreState(CoreState(commandId = "CMD-H1", phase = "error", error = "a turn is already running"), h1Scope)!!.accepted)

        val forgetTracker = SendDraftTracker().apply {
            track("H1", "C", "one", 1, imageScope = h1Scope)
            track("H2", "C", "two", 1, imageScope = h2Scope)
            forgetCodex("C", h2Scope)
        }
        assertNull(forgetTracker.onCoreState(CoreState(commandId = "H2", phase = "error", error = "a turn is already running"), h2Scope))
        assertFalse(forgetTracker.onCoreState(CoreState(commandId = "H1", phase = "error", error = "a turn is already running"), h1Scope)!!.accepted)
        assertEquals(h2Scope, h2.activeImageDraftScope())
    }

    @Test
    fun bitmapSamplingBoundsThumbnailAndDialogDecodeMemory() {
        assertEquals(16, sampledBitmapInSampleSize(8000, 6000, 512, 512L * 512))
        assertEquals(4, sampledBitmapInSampleSize(8000, 6000, 2048, 4_000_000L))
        assertEquals(1, sampledBitmapInSampleSize(400, 300, 512, 512L * 512))
    }

    @Test
    fun everyImageRpcDispatchRequiresCapturedConnectedHostScope() {
        val h1Scope = imageDraftScope("host-one", "C")
        val h1 = AppUiState(
            connectedHostAddress = "host-one",
            openCodexId = "C",
            core = CoreState(selectedCodexId = "C"),
        )
        assertTrue(canDispatchUploadedImageTurn(h1, "C", h1Scope))
        assertFalse(canDispatchUploadedImageTurn(h1.copy(connectedHostAddress = "host-two"), "C", h1Scope))
        assertFalse(canDispatchUploadedImageTurn(h1.copy(openCodexId = null), "C", h1Scope))
        assertFalse(canDispatchUploadedImageTurn(h1.copy(core = h1.core.copy(selectedCodexId = "D")), "C", h1Scope))
    }
}
