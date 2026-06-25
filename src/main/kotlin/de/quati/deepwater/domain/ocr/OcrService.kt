@file:OptIn(ExperimentalUuidApi::class)

package de.quati.deepwater.domain.ocr

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import de.quati.deepwater.domain.common.createImageAttachment
import de.quati.deepwater.domain.common.mapParallel
import de.quati.deepwater.domain.gateway.FilterContext
import de.quati.deepwater.domain.gateway.TextMessage
import de.quati.deepwater.domain.vision.VisionService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory.getLogger
import org.springframework.stereotype.Service
import kotlin.math.log
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


@Service
class OcrService(
    private val config: OcrConfiguration.Properties,
    private val visionService: VisionService,
) {
    companion object {
        private val logger = getLogger(OcrService::class.java)
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
        engine { requestTimeout = 300_000 }
    }
    private val dataUrlRegex = Regex("""^data:([^;,]+)((?:;[^;,=]+=[^;,=]+)*)(;base64)?,(.*)$""")
    private val inlineBase64ImageRegex = Regex(
        pattern = """!\[(.*?)]\((data:image/[^;]+;base64,[^)]+)\)""",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    context(request: FilterContext)
    suspend fun processFile(
        source: AttachmentSource.File,
        embeddImages: Boolean = true,
    ): TextMessage {
        val bytes = when (val content = source.content) {
            is AttachmentContent.Binary.Base64 -> content.asBytes()
            is AttachmentContent.Binary.Bytes -> content.asBytes()
            is AttachmentContent.PlainText -> TODO("not supported yet")
            is AttachmentContent.URL -> TODO("not supported yet")
        }
        val response = client.post("${config.baseUrl}/v1/convert/file") {
            header("X-Api-Key", config.apiKey)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("files", bytes, Headers.build {
                            append(
                                HttpHeaders.ContentDisposition,
                                "form-data; name=\"files\"; filename=\"${source.fileName ?: "file"}\""
                            )
                        })
                        append("to_formats", "md")
                        append("pipeline", "standard")
                        append("image_export_mode", if (embeddImages) "embedded" else "placeholder")
                    }
                )
            )
        }
        val ocrResponse = if (response.status.value in 200..299)
            response.body<OcrResponse>()
        else error("OCR Failed: ${response.bodyAsText()}")

        val content = ocrResponse.document.mdContent ?: ""
        return if (embeddImages) {
            val (minifiedContent, images) = extractAndReplaceImages(content)
            val annotatedContent = annotated(content = minifiedContent, images = images)
            TextMessage(text = annotatedContent)
        } else {
            TextMessage(text = content)
        }
    }

    suspend fun extractAndReplaceImages(
        content: String,
    ): Pair<String, MutableList<ExtractedImage>> {
        val imagesByUuid = mutableListOf<ExtractedImage>()
        val minifiedContent = inlineBase64ImageRegex.replace(content) { match ->
            val dataUri = match.groupValues[2]
            val uuid = Uuid.random()

            val match = dataUrlRegex.matchEntire(dataUri)
            if (match != null) {
                val mimeType = match.groupValues[1]
                val rawParams = match.groupValues[2]
                val isBase64 = match.groupValues[3].isNotEmpty()
                val data = match.groupValues[4]

                val params = Regex(""";([^;,=]+)=([^;,=]+)""")
                    .findAll(rawParams)
                    .associate { it.groupValues[1] to it.groupValues[2] }
                imagesByUuid.add(ExtractedImage(uuid, mimeType, data))
            }
            uuid.toString()
        }
        return minifiedContent to imagesByUuid
    }


    context(_: FilterContext)
    suspend fun annotated(
        content: String,
        images: List<ExtractedImage>,
    ): String {
        val imageAnnotationById = images.asFlow().take(5).mapParallel(5) { img ->
            runCatching {
                val attachment = createImageAttachment(img.uuid.toString(), img.mimeType, img.data)
                val annotation = visionService.processImage(source = attachment)
                img.uuid to annotation
            }
                .onFailure { error ->
                    logger.error("Failed to annotate image with uuid ${img.uuid}", error)
                }
                .getOrNull()
        }.toList().mapNotNull { it }.toMap()
        var text = content
        imageAnnotationById.forEach { (uuid, annotation) ->
            logger.info("Annotate Image: $annotation")
            text = text.replace(uuid.toString(), annotation.text)
        }
        return text
    }

    data class ExtractedImage(
        val uuid: Uuid,
        val mimeType: String,
        val data: String,
    )

    @Serializable
    private data class OcrResponse(
        val document: OcrDocument,
        val status: String,
        val errors: List<String>,
        @SerialName("processing_time") val processingTime: Double,
        val timings: Map<String, OcrTimingInfo> = emptyMap(),
    )

    @Serializable
    private data class OcrTimingInfo(
        val scope: String,
        val count: Int,
        val times: List<Double>,
        @SerialName("start_timestamps") val startTimestamps: List<String>,
    )

    @Serializable
    private data class OcrDocument(
        val filename: String,
        @SerialName("md_content") val mdContent: String? = null,
        @SerialName("json_content") val jsonContent: String? = null,
        @SerialName("html_content") val htmlContent: String? = null,
        @SerialName("text_content") val textContent: String? = null,
        @SerialName("doctags_content") val doctagsContent: String? = null,
    )


}

