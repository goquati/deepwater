package de.quati.deepwater

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
class ProxyFilter : GlobalFilter, Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val mutatedExchange = mutateRequest(exchange)
        val decoratedExchange = mutatedExchange.mutate()
            .response(decorateResponse(mutatedExchange))
            .build()
        return chain.filter(decoratedExchange)
    }

    // ===== REQUEST HOOKS =====
    private fun mutateRequest(exchange: ServerWebExchange): ServerWebExchange {
        val mutatedRequest = exchange.request.mutate()
            // .header("X-Custom-Header", "value")
            // .headers { it.remove("Authorization") }
            // .path("/new/path")
            .build()

        // Body modification skeleton — uncomment to activate:
        //
        // val bodyFlux = exchange.request.body.collectList().flatMapMany { buffers ->
        //     val bytes = buffers.fold(byteArrayOf()) { acc, buf ->
        //         val b = ByteArray(buf.readableByteCount()).also { buf.read(it) }
        //         DataBufferUtils.release(buf)
        //         acc + b
        //     }
        //     val newBytes = bytes  // <-- transform here
        //     Flux.just(exchange.response.bufferFactory().wrap(newBytes))
        // }
        // val bodyRequest = object : ServerHttpRequestDecorator(mutatedRequest) {
        //     override fun getBody(): Flux<DataBuffer> = bodyFlux
        // }
        // return exchange.mutate().request(bodyRequest).build()

        return exchange.mutate().request(mutatedRequest).build()
    }

    // ===== RESPONSE HOOKS =====
    private fun decorateResponse(exchange: ServerWebExchange) =
        object : ServerHttpResponseDecorator(exchange.response) {
            override fun writeWith(body: org.reactivestreams.Publisher<out DataBuffer>): Mono<Void> {
                // Header modifications:
                // delegate.headers.set("X-Proxy", "deepwater")

                // Body passthrough (no modification) — default:
                return super.writeWith(body)

                // Body modification skeleton — replace return above to activate:
                //
                // val buffered = DataBufferUtils.join(Flux.from(body)).flatMapMany { joined ->
                //     val bytes = ByteArray(joined.readableByteCount()).also { joined.read(it) }
                //     DataBufferUtils.release(joined)
                //     val newBytes = bytes  // <-- transform here
                //     Flux.just(bufferFactory().wrap(newBytes))
                // }
                // return super.writeWith(buffered)
            }
        }
}
