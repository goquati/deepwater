package de.quati.deepwater.domain.gateway

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(GatewayConfiguration.Properties::class)
class GatewayConfiguration(
    val properties: Properties,
) {
    @ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux")
    class Properties {
        lateinit var routes: List<Route>
    }

    val route: String
        get() = properties.routes.first { it.id == "openhippo-catch-all" }.uri

    data class Route(
        val id: String,
        val uri: String,
        val predicates: List<String> = listOf(),
        val filters: List<String> = listOf()
    )
}
