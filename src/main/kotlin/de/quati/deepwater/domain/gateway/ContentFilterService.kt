@file:OptIn(ExperimentalUuidApi::class)

package de.quati.deepwater.domain.gateway

import ai.koog.prompt.message.AttachmentSource
import de.quati.deepwater.domain.common.createFileAttachment
import de.quati.deepwater.domain.common.createImageAttachment
import de.quati.deepwater.domain.common.extractBase64Data
import de.quati.deepwater.domain.common.getMimeType
import de.quati.deepwater.domain.ocr.OcrService
import de.quati.deepwater.domain.vision.VisionService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.uuid.ExperimentalUuidApi

@Service
class ContentFilterService(
    private val visionService: VisionService,
    private val ocrService: OcrService,
    private val cacheManager: CacheManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache get() = cacheManager.getCache("base64-cache")!!

    context(_: FilterContext)
    suspend fun filterContentElement(cEl: JsonObject): JsonObject? {
        val type = cEl["type"]?.jsonPrimitive?.content ?: return cEl
        return when (type) {
            "text" -> cEl
            "input_audio" -> null
            "image_url" -> handleImageUrl(cEl)?.let {
                cache.getOrPut(it.first) { visionService.processImage(it.second) }
            }?.toJsonObject()

            "file" -> handleFile(cEl)?.let {
                cache.getOrPut(it.first) { ocrService.processFile(it.second) }
            }?.toJsonObject()

            else -> cEl
        }
    }

    private fun TextMessage.toJsonObject() = json.parseToJsonElement(json.encodeToString(this)).jsonObject
    fun handleImageUrl(el: JsonObject): Pair<CacheKey, AttachmentSource.Image>? {
        val imageElement = el["image_url"]?.jsonObject ?: return null
        val base64 = imageElement["url"]?.jsonPrimitive?.content ?: return null
        val mimeTypeRaw = getMimeType(base64)
        val dataRaw = extractBase64Data(base64)
        val format = mimeTypeRaw.substringAfter("/")
        val attachment = createImageAttachment("", format, dataRaw)
        return dataRaw.computeCacheKey() to attachment
    }

    fun handleFile(el: JsonObject): Pair<CacheKey, AttachmentSource.File>? {
        val fileElement = el["file"]?.jsonObject ?: return null
        val fileName = fileElement["filename"]?.jsonPrimitive?.content ?: return null
        val base64 = fileElement["file_data"]?.jsonPrimitive?.content ?: return null
        val mimeTypeRaw = getMimeType(base64)
        val dataRaw = extractBase64Data(base64)
        val format = mimeTypeRaw.substringAfter("/")
        val attachment = createFileAttachment(fileName, format, mimeTypeRaw, dataRaw)
        return dataRaw.computeCacheKey() to attachment
    }
}

inline fun <reified T : Any> Cache.getOrPut(key: Any, compute: () -> T?): T? =
    get(key, T::class.java) ?: compute().also { put(key, it) }

fun String.computeCacheKey(): CacheKey {
    val hash = MessageDigest.getInstance("SHA-1")
        .digest(this.toByteArray(Charsets.UTF_8))
    return CacheKey(BigInteger(1, hash).toString(16).padStart(40, '0'))
}

@JvmInline
value class CacheKey(val key: String)