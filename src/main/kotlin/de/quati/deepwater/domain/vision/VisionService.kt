package de.quati.deepwater.domain.vision

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import de.quati.deepwater.domain.gateway.FilterContext
import de.quati.deepwater.domain.gateway.GatewayConfiguration
import de.quati.deepwater.domain.gateway.TextMessage
import org.springframework.stereotype.Service

@Service
class VisionService(
    private val properties: ModelConfiguration.Properties,
) {

    context(context: FilterContext)
    suspend fun processImage(
        source: AttachmentSource.Image,
    ): TextMessage {
        val prompt = prompt("") {
            system {
                markdown {
                    markdown {
                        +"Du extrahierst ALLE Informationen aus einem Bild, das aus einem Dokument stammt (Diagramm, Chart, Tabelle, Formular, Foto, Handschrift, Screenshot, Formel u. a.)."
                        br()

                        h2("Regeln")
                        bulleted {
                            item { +"Erfasse alles Sichtbare: Text, Zahlen, Beschriftungen, Strukturen, Symbole, Farben (wenn bedeutungstragend)." }
                            item { +"Transkribiere wortgetreu. Nichts korrigieren, übersetzen oder erfinden." }
                            item { +"Unklares/Abgeschnittenes/Unleserliches markieren statt raten: [unleserlich], [abgeschnitten]." }
                            item { +"Vorhandene Struktur (Tabelle, Diagramm, Formular) erhalten, nicht zu Fließtext verflachen." }
                            item { +"Beobachtung vor Interpretation." }
                            item { +"Antworte immer in deutsch" }
                        }
                        br()

                        h2("Ausgabe")
                        bulleted {
                            item { +"bildtyp: Klassifizierung" }
                            item { +"zusammenfassung: 1–3 Sätze" }
                            item { +"inhalt: vollständige strukturierte Extraktion (Tabelle/Knoten-Kanten-Liste je nach Typ)" }
                            item { +"text: wortgetreue Transkription oder \"keiner\"" }
                            item { +"daten: strukturierte Daten bei Chart/Tabelle/Formular, sonst \"n/v\"" }
                            item { +"unsicherheiten: Unleserliches/Mehrdeutiges, sonst \"keine\"" }
                        }
                        br()
                        +"Nur die Ausgabe im obigen Format, keine Einleitung."
                    }
                }
            }
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
        val text = response.parts.filterIsInstance<MessagePart.Text>()
            .joinToString(" ") { it.text }
            .split("\n")
            .joinToString("\n> ") { it }
        return TextMessage(text = "\n\n> **[Beschreibung Bild]** $text\n\n")
    }

    private fun getClient(
        apiKey: String,
    ): OpenAILLMClient {
        return OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = properties.baseUrl),
            apiKey = apiKey,
        )
    }
}

