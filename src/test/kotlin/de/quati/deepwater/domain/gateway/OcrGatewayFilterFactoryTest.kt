package de.quati.deepwater.domain.gateway

import de.quati.deepwater.domain.ocr.OcrConfiguration
import de.quati.deepwater.domain.ocr.OcrService
import de.quati.deepwater.domain.vision.ModelConfiguration
import de.quati.deepwater.domain.vision.VisionService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that images embedded as base64 data URIs in an OCR response's markdown are
 * extracted, sent to [VisionService] for annotation, and the annotation text is spliced
 * back into the markdown before the response reaches the client.
 */
class OcrGatewayFilterFactoryTest {

    private val visionService = mock<VisionService>()
    private val ocrService = OcrService(
        config = OcrConfiguration.Properties().apply {
            apiKey = "unused"
            baseUrl = "http://unused"
        },
        visionService = visionService,
    )
    private val modelConfiguration = ModelConfiguration.Properties().apply {
        baseUrl = "http://unused"
        vision = "hippo-vision"
        apiKey = "unused"
    }
    private val filterFactory = OcrGatewayFilterFactory(ocrService, modelConfiguration)

    @Test
    fun `extracts embedded images from OCR response and replaces them with vision annotations`() {
        runBlocking {
            with(any<FilterContext>()) {
                whenever(visionService.processImage(any())).thenReturn(TextMessage(text = "A friendly hippo."))
            }
        }

        val base64Image = "aGVsbG8td29ybGQ="
        val downstreamMarkdown = "# Title\n\n![img](data:image/png;base64,$base64Image)\n\nMore text"
        val downstreamJson = Json.encodeToString(
            buildJsonObject {
                putJsonObject("document") {
                    put("filename", "doc.pdf")
                    put("md_content", downstreamMarkdown)
                }
            }
        )

        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/ocr/v1/convert/file")
                .header("Content-Type", "multipart/form-data; boundary=xyz")
                .body("irrelevant")
        )
        val chain = GatewayFilterChain { ex ->
            ex.response.writeWith(Mono.just(ex.response.bufferFactory().wrap(downstreamJson.toByteArray())))
        }

        filterFactory.apply(OcrGatewayFilterFactory.Config()).filter(exchange, chain).block()

        val resultJson = (exchange.response as MockServerHttpResponse).bodyAsString.block()!!
        val resultMarkdown = Json.parseToJsonElement(resultJson)
            .jsonObject["document"]!!.jsonObject["md_content"]!!.jsonPrimitive.content

        assertTrue(resultMarkdown.contains("A friendly hippo."), "annotation should replace the image: $resultMarkdown")
        assertFalse(resultMarkdown.contains("base64"), "base64 payload should be removed: $resultMarkdown")
        assertTrue(resultMarkdown.startsWith("# Title"), "surrounding markdown should be preserved: $resultMarkdown")
        assertTrue(resultMarkdown.trimEnd().endsWith("More text"), "surrounding markdown should be preserved: $resultMarkdown")
    }
}
