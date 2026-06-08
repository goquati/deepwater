package de.quati.deepwater.domain.vision

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import de.quati.deepwater.domain.gateway.FilterContext
import de.quati.deepwater.domain.gateway.GatewayConfiguration
import de.quati.deepwater.domain.gateway.TextMessage
import org.springframework.stereotype.Service

@Service
class VisionService(
    private val properties: VisionModelConfiguration.Properties,
    private val gatewayConfiguration: GatewayConfiguration,
) {

    context(context: FilterContext)
    suspend fun processImage(
        source: AttachmentSource.Image,
    ): TextMessage {
        val prompt = prompt("") {
            user {
                text(context.userMessage)
                image(source)
            }
        }
        val model = LLModel(
            provider = LLMProvider.OpenAI,
            id = properties.vision,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Vision.Image,
                LLMCapability.Schema.JSON.Basic,
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.OpenAIEndpoint.Completions,
                LLMCapability.OpenAIEndpoint.Responses,
            ),
            contextLength = 30_000,
            maxOutputTokens = 30_000
        )
        val client = getClient(context.apiKey)
        val response = client.execute(
            prompt = prompt,
            model = model,
        )
        val text = response.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
        return TextMessage(text = "[Vision model output: $text]")
    }

    private fun getClient(
        apiKey: String,
    ): OpenAILLMClient {
        return OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = gatewayConfiguration.route),
            apiKey = apiKey
        )
    }
}

