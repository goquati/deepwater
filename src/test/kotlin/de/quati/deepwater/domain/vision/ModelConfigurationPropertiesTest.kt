package de.quati.deepwater.domain.vision

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelConfigurationPropertiesTest {

    private fun properties(capabilities: Map<String, List<Capability>>) =
        ModelConfiguration.Properties().apply {
            baseUrl = "http://unused"
            vision = "unused"
            apiKey = "unused"
            this.capabilities = capabilities
        }

    @Test
    fun `returns true when the model is listed with vision capability`() {
        val properties = properties(mapOf("gpt-4o" to listOf(Capability.VISION)))
        assertTrue(properties.hasVisionCapability("gpt-4o"))
    }

    @Test
    fun `returns false when the model is listed without vision capability`() {
        val properties = properties(mapOf("hippo-coding" to emptyList()))
        assertFalse(properties.hasVisionCapability("hippo-coding"))
    }

    @Test
    fun `returns false when the model is absent from the capability map`() {
        val properties = properties(mapOf("gpt-4o" to listOf(Capability.VISION)))
        assertFalse(properties.hasVisionCapability("some-unlisted-model"))
    }
}
