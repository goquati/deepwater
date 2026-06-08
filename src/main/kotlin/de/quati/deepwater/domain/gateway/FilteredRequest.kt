package de.quati.deepwater.domain.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FilteredRequest(
    val model: String,
    val user: String,
    val stream: Boolean,
    val messages: List<Message>
)

@Serializable
data class Message(
    val role: String,
    val content: List<ContentBlock>
)

@Serializable
sealed class ContentBlock {
    abstract val type: String
}

@Serializable
@SerialName("text")
data class TextContentBlock(
    override val type: String,
    val text: String
) : ContentBlock()

@Serializable
@SerialName("file")
data class FileContentBlock(
    override val type: String,
    val file: FileData
) : ContentBlock()

@Serializable
data class FileData(
    val filename: String,
    @SerialName("file_data") val fileData: String
)