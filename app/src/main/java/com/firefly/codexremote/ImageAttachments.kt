package com.firefly.codexremote

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class DraftImageAttachment(
    val localId: String,
    val codexId: String,
    val filename: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val widthPixels: Int,
    val heightPixels: Int,
    val originalPath: String = "",
    val originalMimeType: String = "",
    val originalSizeBytes: Long = 0,
    val originalSha256: String = "",
    val scope: String = "",
    val uploadCommandId: String = "",
)

data class ImageImportLimits(
    val maxUploadBytes: Long,
    val supportedMimeTypes: Set<String>,
)

class ImageAttachmentException(message: String) : Exception(message)

/** Copies a transient picker/clipboard URI into app-private storage before returning. */
fun importImageAttachment(
    resolver: ContentResolver,
    filesDir: File,
    uri: Uri,
    codexId: String,
    hostAddress: String,
    limits: ImageImportLimits,
): DraftImageAttachment {
    if (hostAddress.isBlank() || codexId.isBlank()) throw ImageAttachmentException("当前未连接到可用会话")
    if (limits.maxUploadBytes <= 0) throw ImageAttachmentException("服务端未提供有效的图片大小上限")
    val sourceMime = resolver.getType(uri)?.substringBefore(';')?.lowercase().orEmpty()
    if (!sourceMime.startsWith("image/")) throw ImageAttachmentException("请选择图片文件")
    val inputLimit = (limits.maxUploadBytes.coerceAtLeast(1024 * 1024) * 8)
        .coerceAtMost(64L * 1024 * 1024)
    val source = resolver.openInputStream(uri)?.use { it.readBytesBounded(inputLimit) }
        ?: throw ImageAttachmentException("无法读取所选图片")
    val localId = UUID.randomUUID().toString()
    val originalExtension = sourceMime.substringAfter('/').replace(Regex("[^a-z0-9]"), "").take(8).ifBlank { "bin" }
    val originalFile = File(filesDir, "image-attachments/originals/$localId.$originalExtension")
    atomicWrite(originalFile, source)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        originalFile.delete()
        throw ImageAttachmentException("图片格式无法解析")
    }
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 4096 || bounds.outHeight / sampleSize > 4096 ||
        (bounds.outWidth.toLong() / sampleSize) * (bounds.outHeight.toLong() / sampleSize) > 16_000_000L
    ) sampleSize *= 2
    val decoded = try {
        BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } catch (_: OutOfMemoryError) {
        null
    }
        ?: run {
            originalFile.delete()
            throw ImageAttachmentException("图片格式无法解析")
        }
    val preferredMime = chooseOutputMime(sourceMime, limits.supportedMimeTypes) ?: run {
        originalFile.delete()
        throw ImageAttachmentException("服务端不支持此图片格式")
    }
    val decodedWidth = decoded.width
    val decodedHeight = decoded.height
    val encoded = try {
        encodeBoundedImage(decoded, preferredMime, limits.maxUploadBytes)
    } finally {
        if (!decoded.isRecycled) decoded.recycle()
    } ?: run {
        originalFile.delete()
        throw ImageAttachmentException("压缩后仍超过 ${formatByteLimit(limits.maxUploadBytes)}")
    }
    val extension = imageExtension(encoded.first)
    val directory = File(filesDir, "image-attachments/drafts").apply { mkdirs() }
    val target = File(directory, "$localId.$extension")
    try {
        atomicWrite(target, encoded.second)
    } catch (error: Exception) {
        originalFile.delete()
        throw error
    }
    val displayName = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { "image-$localId.$extension" }
    return DraftImageAttachment(
        localId, codexId, displayName, encoded.first, target.absolutePath,
        encoded.second.size.toLong(), sha256Hex(encoded.second), decodedWidth, decodedHeight,
        originalFile.absolutePath, sourceMime, source.size.toLong(), sha256Hex(source),
        imageDraftScope(hostAddress, codexId), UUID.randomUUID().toString(),
    )
}

internal fun chooseOutputMime(sourceMime: String, supported: Set<String>): String? = when {
    sourceMime in supported && sourceMime in EncodableImageMimeTypes -> sourceMime
    "image/jpeg" in supported -> "image/jpeg"
    "image/png" in supported -> "image/png"
    else -> null
}

private fun encodeBoundedImage(bitmap: Bitmap, mime: String, limit: Long): Pair<String, ByteArray>? {
    return try {
        encodeBoundedImageUnchecked(bitmap, mime, limit)
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun encodeBoundedImageUnchecked(bitmap: Bitmap, mime: String, limit: Long): Pair<String, ByteArray>? {
    var current = bitmap
    repeat(7) { pass ->
        val output = ByteArrayOutputStream()
        val format = when (mime) {
            "image/png" -> Bitmap.CompressFormat.PNG
            "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.JPEG
        }
        val quality = if (format == Bitmap.CompressFormat.PNG) 100 else (92 - pass * 11).coerceAtLeast(35)
        current.compress(format, quality, output)
        val bytes = output.toByteArray()
        if (bytes.size <= limit) {
            if (current !== bitmap && !current.isRecycled) current.recycle()
            return mime to bytes
        }
        if (current.width <= 320 || current.height <= 320) {
            if (current !== bitmap && !current.isRecycled) current.recycle()
            return null
        }
        val scale = 0.78f
        val scaled = Bitmap.createScaledBitmap(
            current,
            (current.width * scale).toInt().coerceAtLeast(1),
            (current.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== current && !current.isRecycled) current.recycle()
        current = scaled
    }
    if (current !== bitmap && !current.isRecycled) current.recycle()
    return null
}

internal fun persistDraftImages(filesDir: File, drafts: Map<String, List<DraftImageAttachment>>) {
    val root = JSONObject()
    drafts.toSortedMap().forEach { (scope, images) ->
        if (images.isNotEmpty() && images.all { it.scope == scope }) {
            root.put(scope, JSONArray().apply { images.forEach { put(it.toJson()) } })
        }
    }
    atomicWrite(File(filesDir, "image-attachments/drafts.json"), root.toString().toByteArray())
}

internal fun loadDraftImages(filesDir: File): Map<String, List<DraftImageAttachment>> = runCatching {
    val root = JSONObject(File(filesDir, "image-attachments/drafts.json").takeIf(File::isFile)?.readText().orEmpty().ifBlank { "{}" })
    buildMap {
        root.keys().forEach { scope ->
            val array = root.optJSONArray(scope) ?: return@forEach
            val images = buildList {
                repeat(array.length()) { index ->
                    val value = array.optJSONObject(index) ?: return@repeat
                    DraftImageAttachment(
                        localId = value.optString("localId"), codexId = value.optString("codexId"),
                        filename = value.optString("filename"), mimeType = value.optString("mimeType"),
                        localPath = value.optString("localPath"), sizeBytes = value.optLong("sizeBytes"),
                        sha256 = value.optString("sha256"), widthPixels = value.optInt("widthPixels"),
                        heightPixels = value.optInt("heightPixels"),
                        originalPath = value.optString("originalPath"),
                        originalMimeType = value.optString("originalMimeType"),
                        originalSizeBytes = value.optLong("originalSizeBytes"),
                        originalSha256 = value.optString("originalSha256"),
                        scope = value.optString("scope"),
                        uploadCommandId = value.optString("uploadCommandId"),
                    ).takeIf {
                        val file = File(it.localPath)
                        val draftRoot = File(filesDir, "image-attachments/drafts").canonicalFile
                        val original = File(it.originalPath)
                        val originalRoot = File(filesDir, "image-attachments/originals").canonicalFile
                        file.isFile && file.canonicalFile.toPath().startsWith(draftRoot.toPath()) &&
                            file.length() == it.sizeBytes && it.mimeType in setOf("image/jpeg", "image/png", "image/webp") &&
                            sha256Hex(file.readBytes()) == it.sha256 && original.isFile &&
                            original.canonicalFile.toPath().startsWith(originalRoot.toPath()) &&
                            original.length() == it.originalSizeBytes && sha256Hex(original.readBytes()) == it.originalSha256 &&
                            it.scope == scope && it.scope.isNotBlank() && it.codexId.isNotBlank() && it.uploadCommandId.isNotBlank()
                    }?.let(::add)
                }
            }
            if (images.isNotEmpty()) put(scope, images)
        }
    }
}.getOrDefault(emptyMap())

private fun DraftImageAttachment.toJson() = JSONObject()
    .put("localId", localId).put("codexId", codexId).put("scope", scope)
    .put("uploadCommandId", uploadCommandId).put("filename", filename).put("mimeType", mimeType)
    .put("localPath", localPath).put("sizeBytes", sizeBytes).put("sha256", sha256)
    .put("widthPixels", widthPixels).put("heightPixels", heightPixels)
    .put("originalPath", originalPath).put("originalMimeType", originalMimeType)
    .put("originalSizeBytes", originalSizeBytes).put("originalSha256", originalSha256)

internal fun cacheDownloadedImage(filesDir: File, cacheScope: String, result: ImageAttachmentDownloadResult): File {
    val descriptor = result.attachment
    val bytes = runCatching { java.util.Base64.getDecoder().decode(result.contentBase64) }
        .getOrElse { throw ImageAttachmentException("下载图片内容无效") }
    if (descriptor.attachmentId.isBlank() || descriptor.sizeBytes != bytes.size.toLong() ||
        !descriptor.sha256.equals(sha256Hex(bytes), ignoreCase = true)
    ) throw ImageAttachmentException("下载图片校验失败")
    val extension = imageExtension(descriptor.mimeType)
    val scope = sha256Hex(cacheScope.toByteArray()).take(24)
    val stableName = sha256Hex(descriptor.attachmentId.toByteArray())
    val target = File(filesDir, "image-attachments/cache/$scope/$stableName.$extension")
    atomicWrite(target, bytes)
    persistCacheDescriptor(File(target.parentFile, "index.json"), descriptor)
    return target
}

private fun persistCacheDescriptor(index: File, descriptor: ImageAttachmentDescriptor) {
    val root = runCatching { JSONObject(index.takeIf(File::isFile)?.readText().orEmpty().ifBlank { "{}" }) }
        .getOrDefault(JSONObject())
    root.put(descriptor.attachmentId, JSONObject().put("filename", descriptor.filename)
        .put("mimeType", descriptor.mimeType).put("sizeBytes", descriptor.sizeBytes)
        .put("sha256", descriptor.sha256).put("widthPixels", descriptor.widthPixels)
        .put("heightPixels", descriptor.heightPixels))
    atomicWrite(index, root.toString().toByteArray())
}

internal fun loadCacheDescriptorIndex(filesDir: File, cacheScope: String): Map<String, ImageAttachmentDescriptor> = runCatching {
    val scope = sha256Hex(cacheScope.toByteArray()).take(24)
    val root = JSONObject(File(filesDir, "image-attachments/cache/$scope/index.json").readText())
    buildMap {
        root.keys().forEach { attachmentId ->
            val value = root.optJSONObject(attachmentId) ?: return@forEach
            put(attachmentId, ImageAttachmentDescriptor(
                attachmentId = attachmentId, filename = value.optString("filename"),
                mimeType = value.optString("mimeType"), sizeBytes = value.optLong("sizeBytes"),
                sha256 = value.optString("sha256"),
                widthPixels = value.optInt("widthPixels").takeIf { value.has("widthPixels") },
                heightPixels = value.optInt("heightPixels").takeIf { value.has("heightPixels") },
            ))
        }
    }
}.getOrDefault(emptyMap())

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun atomicWrite(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
    temporary.writeBytes(bytes)
    runCatching {
        Files.move(
            temporary.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
        )
    }.recoverCatching {
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }.getOrElse {
        temporary.delete()
        throw ImageAttachmentException("无法保存图片缓存")
    }
}

private fun InputStream.readBytesBounded(limit: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw ImageAttachmentException("原始图片过大，请选择较小的图片")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun existingCachedImage(
    filesDir: File,
    cacheScope: String,
    descriptor: ImageAttachmentDescriptor,
): File? {
    val extension = imageExtension(descriptor.mimeType)
    val scope = sha256Hex(cacheScope.toByteArray()).take(24)
    val stableName = sha256Hex(descriptor.attachmentId.toByteArray())
    return File(filesDir, "image-attachments/cache/$scope/$stableName.$extension").takeIf {
        it.isFile && it.length() == descriptor.sizeBytes && sha256Hex(it.readBytes()).equals(descriptor.sha256, true)
    }
}

private val EncodableImageMimeTypes = setOf("image/jpeg", "image/png", "image/webp")
private fun imageExtension(mimeType: String) = when (mimeType) {
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "jpg"
}

internal fun imageCacheScope(hostAddress: String, codexId: String): String =
    hostAddress.trim() + "\n" + codexId.trim()

internal fun imageDraftScope(hostAddress: String, codexId: String): String =
    imageCacheScope(hostAddress, codexId)

internal fun imageCacheKey(cacheScope: String, attachmentId: String): String =
    sha256Hex(cacheScope.toByteArray()).take(24) + ":" + attachmentId

internal fun archiveOriginalImage(filesDir: File, cacheScope: String, image: DraftImageAttachment) {
    val original = File(image.originalPath)
    if (!original.isFile) return
    val scope = sha256Hex(cacheScope.toByteArray()).take(24)
    val extension = image.originalMimeType.substringAfter('/').replace(Regex("[^a-z0-9]"), "").take(8).ifBlank { "bin" }
    val target = File(filesDir, "image-attachments/archive/$scope/${image.originalSha256}.$extension")
    atomicWrite(target, original.readBytes())
    original.delete()
}

internal data class ArchivedOriginal(
    val attachment: ImageAttachmentDescriptor,
    val originalPath: String,
    val originalMimeType: String,
    val originalSizeBytes: Long,
    val originalSha256: String,
)

internal fun archiveUploadedOriginal(
    filesDir: File,
    cacheScope: String,
    descriptor: ImageAttachmentDescriptor,
    image: DraftImageAttachment,
): ArchivedOriginal {
    val original = File(image.originalPath)
    if (!original.isFile || original.length() != image.originalSizeBytes ||
        !sha256Hex(original.readBytes()).equals(image.originalSha256, true)
    ) throw ImageAttachmentException("原始图片恢复副本校验失败")
    val scope = sha256Hex(cacheScope.toByteArray()).take(24)
    val attachmentKey = sha256Hex(descriptor.attachmentId.toByteArray())
    val extension = image.originalMimeType.substringAfter('/').replace(Regex("[^a-z0-9]"), "").take(8).ifBlank { "bin" }
    val target = File(filesDir, "image-attachments/archive/$scope/$attachmentKey/original.$extension")
    atomicWrite(target, original.readBytes())
    val index = File(filesDir, "image-attachments/archive/$scope/index.json")
    val root = runCatching { JSONObject(index.takeIf(File::isFile)?.readText().orEmpty().ifBlank { "{}" }) }
        .getOrDefault(JSONObject())
    root.put(descriptor.attachmentId, JSONObject()
        .put("filename", descriptor.filename).put("mimeType", descriptor.mimeType)
        .put("sizeBytes", descriptor.sizeBytes).put("sha256", descriptor.sha256)
        .put("widthPixels", descriptor.widthPixels).put("heightPixels", descriptor.heightPixels)
        .put("originalPath", target.absolutePath).put("originalMimeType", image.originalMimeType)
        .put("originalSizeBytes", image.originalSizeBytes).put("originalSha256", image.originalSha256))
    atomicWrite(index, root.toString().toByteArray())
    return ArchivedOriginal(descriptor, target.absolutePath, image.originalMimeType, image.originalSizeBytes, image.originalSha256)
}

internal fun archivedOriginalFor(
    filesDir: File,
    cacheScope: String,
    descriptor: ImageAttachmentDescriptor,
): ArchivedOriginal? = runCatching {
    val scope = sha256Hex(cacheScope.toByteArray()).take(24)
    val value = JSONObject(File(filesDir, "image-attachments/archive/$scope/index.json").readText())
        .getJSONObject(descriptor.attachmentId)
    val indexed = ImageAttachmentDescriptor(
        descriptor.attachmentId, value.optString("filename"), value.optString("mimeType"),
        value.optLong("sizeBytes"), value.optString("sha256"),
        value.optInt("widthPixels").takeIf { value.has("widthPixels") },
        value.optInt("heightPixels").takeIf { value.has("heightPixels") },
    )
    if (indexed != descriptor) return@runCatching null
    val original = File(value.getString("originalPath"))
    val archiveRoot = File(filesDir, "image-attachments/archive/$scope").canonicalFile
    val originalSize = value.getLong("originalSizeBytes")
    val originalHash = value.getString("originalSha256")
    if (!original.isFile || !original.canonicalFile.toPath().startsWith(archiveRoot.toPath()) ||
        original.length() != originalSize || !sha256Hex(original.readBytes()).equals(originalHash, true)
    ) return@runCatching null
    ArchivedOriginal(indexed, original.absolutePath, value.getString("originalMimeType"), originalSize, originalHash)
}.getOrNull()

internal fun sampledBitmapInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxPixels: Long,
): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0 || maxPixels <= 0) return 1
    var sample = 1
    while (width / sample > maxDimension || height / sample > maxDimension ||
        (width.toLong() / sample) * (height.toLong() / sample) > maxPixels
    ) sample *= 2
    return sample
}

internal fun decodeSampledBitmap(path: String, maxDimension: Int, maxPixels: Long): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = sampledBitmapInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxPixels)
        },
    )
} catch (_: OutOfMemoryError) {
    null
}
