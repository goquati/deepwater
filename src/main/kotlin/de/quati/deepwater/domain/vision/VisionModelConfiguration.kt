package de.quati.deepwater.domain.vision

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(VisionModelConfiguration.Properties::class)
class VisionModelConfiguration(
    val properties: Properties,
) {
    @ConfigurationProperties(prefix = "model")
    class Properties {
        lateinit var baseUrl: String
        lateinit var vision: String
        lateinit var apiKey: String
    }

    val modelId: VisionModelId
        get() = VisionModelId(properties.vision)
}

@JvmInline
value class VisionModelId(val value: String)