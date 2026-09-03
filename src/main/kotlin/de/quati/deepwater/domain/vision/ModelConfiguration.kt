package de.quati.deepwater.domain.vision

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(ModelConfiguration.Properties::class)
class ModelConfiguration {
    @ConfigurationProperties(prefix = "model")
    class Properties {
        lateinit var baseUrl: String
        lateinit var vision: String
        lateinit var apiKey: String
        var visionPrompt: String? = null
        var capabilities: Map<String, List<Capability>> = emptyMap()

        fun hasVisionCapability(modelId: String): Boolean =
            capabilities[modelId]?.contains(Capability.VISION) ?: false
    }
}
