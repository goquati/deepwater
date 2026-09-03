package de.quati.deepwater.domain.gateway

import de.quati.deepwater.domain.common.createImageAttachment
import de.quati.deepwater.domain.ocr.OcrConfiguration
import de.quati.deepwater.domain.ocr.OcrService
import de.quati.deepwater.domain.vision.VisionService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import kotlin.test.assertEquals

/**
 * Verifies that an OpenAI-style chat request carrying a base64 image (`image_url` content
 * block) is routed through [VisionService] and the image is replaced by the model's
 * annotation before the request is forwarded upstream.
 */
class LlmGatewayFilterFactoryTest {

    private val visionService = mock<VisionService>()
    private val ocrService = OcrService(
        config = OcrConfiguration.Properties().apply {
            apiKey = "unused"
            baseUrl = "http://unused"
        },
        visionService = visionService,
    )
    private val cacheManager = ConcurrentMapCacheManager("base64-cache")
    private val contentFilterService = ContentFilterService(visionService, ocrService, cacheManager)
    private val filterFactory = LlmGatewayFilterFactory(contentFilterService)

    @Test
    fun `replaces base64 image content with vision model annotation`() {
        val base64Image = "aGVsbG8td29ybGQ="
        val requestBody = """
            {
              "model": "hippo-vision",
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": "What's in this image?"},
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64,$base64Image"}}
                  ]
                }
              ]
            }
        """.trimIndent()

        val expectedAttachment = createImageAttachment("", "png", base64Image)
        val filterContext = FilterContext(
            apiKey = "Bearer test-key",
            model = "hippo-vision",
            userMessage = "What's in this image?",
        )
        runBlocking {
            with(filterContext) {
                whenever(visionService.processImage(expectedAttachment))
                    .thenReturn(TextMessage(text = "A friendly hippo."))
            }
        }

        val exchange = buildExchange(requestBody, authorization = "Bearer test-key")
        val mutatedExchange = invokeFilter(exchange)
        val content = readRequestJson(mutatedExchange)
            .jsonObject["messages"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonArray

        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("What's in this image?", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("A friendly hippo.", content[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    private fun buildExchange(body: String, authorization: String): ServerWebExchange {
        val request = MockServerHttpRequest.post("/v1/chat/completions")
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .body(body)
        return MockServerWebExchange.from(request)
    }

    private fun invokeFilter(exchange: ServerWebExchange): ServerWebExchange {
        var captured: ServerWebExchange? = null
        val chain = GatewayFilterChain { ex ->
            captured = ex
            Mono.empty()
        }
        filterFactory.apply(LlmGatewayFilterFactory.Config()).filter(exchange, chain).block()
        return captured ?: error("filter chain was never invoked")
    }

    private fun readRequestJson(exchange: ServerWebExchange) =
        Json.parseToJsonElement(readBody(exchange.request.body))

    private fun readBody(body: org.reactivestreams.Publisher<out org.springframework.core.io.buffer.DataBuffer>): String {
        val joined = DataBufferUtils.join(body).block()!!
        val bytes = ByteArray(joined.readableByteCount()).also { joined.read(it) }
        DataBufferUtils.release(joined)
        return String(bytes, Charsets.UTF_8)
    }
}
