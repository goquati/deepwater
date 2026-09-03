package de.quati.deepwater.domain.gateway

import de.quati.deepwater.domain.vision.ModelConfiguration
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
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
class LlmGatewayFilterFactory(
    private val contentFilterService: ContentFilterService,
    private val modelConfiguration: ModelConfiguration.Properties,
) : AbstractGatewayFilterFactory<LlmGatewayFilterFactory.Config>(Config::class.java) {

    private val json = Json { ignoreUnknownKeys = true }

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
        val authHeader = exchange.request.headers.getFirst("Authorization") ?: ""
        val joined = DataBufferUtils.join(exchange.request.body).awaitSingleOrNull() ?: return exchange

        val bytes = ByteArray(joined.readableByteCount()).also { joined.read(it) }
        DataBufferUtils.release(joined)

        val raw = String(bytes, Charsets.UTF_8)
        val body = try {
            val bodyEl = json.parseToJsonElement(raw).jsonObject
            val model = bodyEl["model"]!!.jsonPrimitive.content
            val messages = bodyEl["messages"]!!.jsonArray

            messages
                .map(JsonElement::jsonObject)
                .map { messagesEl ->
                    try {
                        if (messagesEl["role"]!!.jsonPrimitive.content != "user") return@map messagesEl
                        val content = messagesEl["content"]!!
                        if (content !is JsonArray) return@map messagesEl

                        val userMessage = content
                            .find { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                            .let { it?.jsonObject["text"]?.jsonPrimitive?.content } ?: return@map messagesEl

                        val filterContext = FilterContext(
                            apiKey = authHeader,
                            model = model,
                            userMessage = userMessage,
                            hasNativeVision = modelConfiguration.hasVisionCapability(model),
                        )

                        val content2: List<JsonObject> = content
                            .map(JsonElement::jsonObject)
                            .mapNotNull content@{ cEl ->
                                with(filterContext) {
                                    contentFilterService.filterContentElement(cEl)
                                }
                            }
                        JsonObject(messagesEl + ("content" to JsonArray(content2)))
                    } catch (e: Exception) {
                        messagesEl
                    }
                }
                .let { JsonArray(it) }
                .let { JsonObject(bodyEl + ("messages" to it)) }
                .let { json.encodeToString(it) }
        } catch (_: Exception) {
            raw
        }

        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val bodyRequest = object : ServerHttpRequestDecorator(exchange.request) {
            override fun getBody(): Flux<DataBuffer> = Flux.just(exchange.response.bufferFactory().wrap(bodyBytes))
            override fun getHeaders(): HttpHeaders = HttpHeaders().also {
                it.addAll(super.getHeaders())
                it.contentLength = bodyBytes.size.toLong()
            }
        }
        return exchange.mutate().request(bodyRequest).build()
    }

    private fun decorateResponse(exchange: ServerWebExchange) =
        object : ServerHttpResponseDecorator(exchange.response) {
            override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
                return super.writeWith(body)
            }
        }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TextMessage(
    @EncodeDefault val type: String = "text",
    val text: String
)

data class FilterContext(
    val model: String,
    val userMessage: String,
    val apiKey: String,
    val hasNativeVision: Boolean,
)
