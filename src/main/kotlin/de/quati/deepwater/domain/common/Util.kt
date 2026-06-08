package de.quati.deepwater.domain.common

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlin.io.encoding.Base64

fun base64ToAttachmentSource(
    base64String: String,
    fileName: String,
): AttachmentSource {
    val mimeTypeRaw = getMimeType(base64String)
    val format = mimeTypeRaw.substringAfter("/")
    val dataRaw = extractBase64Data(base64String)

    return when (classifyMimeType(mimeTypeRaw)) {
        MimeClassification.IMAGE -> createImageAttachment(fileName, format, dataRaw)
        MimeClassification.VIDEO -> createVideoAttachment(fileName, format, dataRaw)
        MimeClassification.AUDIO -> createAudioAttachment(fileName, format, dataRaw)
        MimeClassification.FILE -> createFileAttachment(fileName, format, mimeTypeRaw, dataRaw)
    }
}

fun createImageAttachment(
    fileName: String,
    format: String,
    dataRaw: String,
): AttachmentSource.Image = AttachmentSource.Image(
    content = AttachmentContent.Binary.Base64(dataRaw),
    format = format,
    fileName = fileName,
)

private fun createVideoAttachment(
    fileName: String,
    format: String,
    dataRaw: String,
): AttachmentSource.Video = AttachmentSource.Video(
    content = AttachmentContent.Binary.Base64(dataRaw),
    format = format,
    fileName = fileName,
)

private fun createAudioAttachment(
    fileName: String,
    format: String,
    dataRaw: String,
): AttachmentSource.Audio = AttachmentSource.Audio(
    content = AttachmentContent.Binary.Base64(dataRaw),
    format = format,
    fileName = fileName,
)

fun createFileAttachment(
    fileName: String,
    format: String,
    mimeType: String,
    dataRaw: String,
): AttachmentSource.File = AttachmentSource.File(
    content = AttachmentContent.Binary.Base64(dataRaw),
    format = format,
    mimeType = mimeType,
    fileName = fileName,
)


enum class MimeClassification {
    IMAGE, VIDEO, AUDIO, FILE
}

fun classifyMimeType(mimeType: String): MimeClassification {
    return when {
        mimeType.startsWith("image/") -> MimeClassification.IMAGE
        mimeType.startsWith("video/") -> MimeClassification.VIDEO
        mimeType.startsWith("audio/") -> MimeClassification.AUDIO
        else -> MimeClassification.FILE
    }
}

fun getMimeType(base64String: String): String {
    if (base64String.startsWith("data:")) {
        return extractMimeType(base64String) ?: detectMimeTypeFromBase64(base64String)
    }
    return detectMimeTypeFromBase64(base64String)
}

fun extractBase64Data(base64String: String): String {
    return if (base64String.contains(",")) {
        base64String.substringAfter(",")
    } else {
        base64String
    }
}

fun extractMimeType(base64String: String): String? {
    return if (base64String.startsWith("data:")) {
        base64String.substringAfter("data:")
            .substringBefore(";")
    } else {
        null
    }
}

fun detectMimeTypeFromBase64(base64: String): String {
    val bytes = Base64.decode(base64.take(16))
    return when {
        bytes.startsWith(byteArrayOf(0x25, 0x50, 0x44, 0x46)) -> "application/pdf"
        bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) -> "image/jpeg"
        bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) -> "image/png"
        bytes.startsWith(byteArrayOf(0x47, 0x49, 0x46)) -> "image/gif"
        bytes.startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) -> "application/zip"
        else -> "application/octet-stream"
    }
}

fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (this.size < prefix.size) return false
    return prefix.indices.all { this[it] == prefix[it] }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
public fun <T, R> Flow<T>.mapParallel(
    concurrency: Int = DEFAULT_CONCURRENCY,
    block: suspend (T) -> R,
): Flow<R> = flatMapMerge(concurrency = concurrency) { flow { emit(block(it)) } }
