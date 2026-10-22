package es.inled.sayri.data

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val author: String,
    var content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatCompletionRequest(
    val model: String = "sayri",
    val messages: List<ChatRoleMessage>,
    val stream: Boolean = true,
    @SerializedName("session_id") val sessionId: String? = null
)

data class SessionSummary(
    val id: String,
    val title: String,
    @SerializedName("agent_id") val agentId: String,
    @SerializedName("created_at") val createdAt: Double,
    @SerializedName("updated_at") val updatedAt: Double,
    @SerializedName("msg_count") val msgCount: Int
)

data class SessionListResponse(
    val sessions: List<SessionSummary>
)

data class SessionDetailResponse(
    val session: SessionDetail
)

data class SessionDetail(
    val id: String,
    val title: String,
    @SerializedName("agent_id") val agentId: String,
    @SerializedName("created_at") val createdAt: Double,
    @SerializedName("updated_at") val updatedAt: Double,
    val messages: List<SessionMessage>
)

data class SessionMessage(
    val role: String,
    val content: String,
    val timestamp: Double = 0.0
)

data class ChatRoleMessage(
    val role: String,
    val content: String
)

data class ChatChunk(
    val id: String?,
    val choices: List<ChatChunkChoice>?
)

data class ChatChunkChoice(
    val index: Int?,
    val delta: ChatChunkDelta?,
    @SerializedName("finish_reason") val finishReason: String?
)

data class ChatChunkDelta(
    val content: String?
)

data class SayriStatusResponse(
    val status: String,
    val version: String,
    @SerializedName("active_agent") val activeAgent: String,
    @SerializedName("uptime_seconds") val uptimeSeconds: Double
)

data class MemoryActionResponse(
    val ok: Boolean,
    val message: String?
)

data class UiElementInfo(
    val id: String?,
    val type: String?,
    val text: String?,
    @SerializedName("content_description") val contentDescription: String?,
    val bounds: List<Int>, // [left, top, right, bottom]
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean
)

data class MobileScreenSnapshot(
    @SerializedName("package_name") val packageName: String,
    val elements: List<UiElementInfo>,
    val intent: String = ""
)

data class MobileActionResponse(
    val ok: Boolean,
    val decision: String?,
    @SerializedName("package_name") val packageName: String?
)
