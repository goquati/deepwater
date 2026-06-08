package de.quati.deepwater.domain.ocr

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration


@Configuration
@EnableConfigurationProperties(OcrConfiguration.Properties::class)
class OcrConfiguration {

    @ConfigurationProperties(prefix = "ocr")
    class Properties {
        lateinit var apiKey: String
    }

}
