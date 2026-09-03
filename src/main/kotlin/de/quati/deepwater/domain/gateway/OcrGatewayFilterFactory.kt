package de.quati.deepwater.domain.gateway

import de.quati.deepwater.domain.ocr.OcrService
import de.quati.deepwater.domain.vision.ModelConfiguration
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.serialization.json.*
import org.reactivestreams.Publisher
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
class OcrGatewayFilterFactory(
    private val ocrService: OcrService,
    private val modelConfiguration: ModelConfiguration.Properties,
) : AbstractGatewayFilterFactory<OcrGatewayFilterFactory.Config>(Config::class.java) {
    class Config

    override fun apply(config: Config): GatewayFilter = OrderedGatewayFilter(
        { exchange, chain ->
            mono {
                val mutatedExchange = mutateRequest(exchange)
                val decoratedExchange = mutatedExchange.mutate()
                    .response(decorateResponse(mutatedExchange))
                    .build()
                chain.filter(decoratedExchange).awaitSingleOrNull()
            }.then()
        },
        Ordered.HIGHEST_PRECEDENCE + 1
    )

    private suspend fun mutateRequest(exchange: ServerWebExchange): ServerWebExchange {
        val contentType = exchange.request.headers.contentType?.toString() ?: return exchange
        val boundary = Regex("""boundary=([^\s;]+)""").find(contentType)?.groupValues?.get(1) ?: return exchange

        val joined = DataBufferUtils.join(exchange.request.body).awaitSingleOrNull() ?: return exchange
        val originalBytes = ByteArray(joined.readableByteCount()).also { joined.read(it) }
        DataBufferUtils.release(joined)

        val closingBoundary = "--$boundary--".toByteArray(Charsets.UTF_8)
        val closingIndex = indexOf(originalBytes, closingBoundary)
        if (closingIndex == -1) return exchange

        val newPart = ("--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"image_export_mode\"\r\n" +
                "\r\n" +
                "embedded\r\n").toByteArray(Charsets.UTF_8)

        val newBytes = ByteArray(closingIndex + newPart.size + closingBoundary.size + 2)
        System.arraycopy(originalBytes, 0, newBytes, 0, closingIndex)
        System.arraycopy(newPart, 0, newBytes, closingIndex, newPart.size)
        System.arraycopy(closingBoundary, 0, newBytes, closingIndex + newPart.size, closingBoundary.size)
        newBytes[newBytes.size - 2] = '\r'.code.toByte()
        newBytes[newBytes.size - 1] = '\n'.code.toByte()

        val bodyRequest = object : ServerHttpRequestDecorator(exchange.request) {
            override fun getBody(): Flux<DataBuffer> = Flux.just(exchange.response.bufferFactory().wrap(newBytes))
            override fun getHeaders(): HttpHeaders = HttpHeaders().also {
                it.addAll(super.getHeaders())
                it.contentLength = newBytes.size.toLong()
            }
        }
        return exchange.mutate().request(bodyRequest).build()
    }

    private fun indexOf(source: ByteArray, target: ByteArray): Int {
        outer@ for (i in 0..source.size - target.size) {
            for (j in target.indices) {
                if (source[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun decorateResponse(exchange: ServerWebExchange) =
        object : ServerHttpResponseDecorator(exchange.response) {
            override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
                return DataBufferUtils.join(Flux.from(body)).flatMap { dataBuffer ->
                    mono {
                        val bytes = ByteArray(dataBuffer.readableByteCount()).also { dataBuffer.read(it) }
                        DataBufferUtils.release(dataBuffer)
                        var body = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
                        val parsedMarkdown = body["document"]?.jsonObject?.get("md_content")?.jsonPrimitive?.content
                        if (parsedMarkdown != null) {
                            val annotatedMarkdown = annotateImages(parsedMarkdown)
                            val updatedDocument = JsonObject(body["document"]!!.jsonObject + ("md_content" to JsonPrimitive(annotatedMarkdown)))
                            body = JsonObject(body + ("document" to updatedDocument))
                        }

                        val responseBytes = Json.encodeToString(body).toByteArray(Charsets.UTF_8)
                        delegate.headers.contentLength = responseBytes.size.toLong()
                        val wrapped = exchange.response.bufferFactory().wrap(responseBytes)
                        super.writeWith(Mono.just(wrapped)).awaitSingleOrNull()
                    }
                }.then()
            }
        }

    private suspend fun annotateImages(markdown: String): String {
        val filterContext = FilterContext(
            userMessage = "",
            model = modelConfiguration.vision,
            apiKey = modelConfiguration.apiKey,
            hasNativeVision = modelConfiguration.hasVisionCapability(modelConfiguration.vision),
        )
        val (minifiedContent, images) = ocrService.extractAndReplaceImages(markdown)
        val annotatedContent = with(filterContext) {
            ocrService.annotated(content = minifiedContent, images = images)
        }
        return annotatedContent
    }
}
