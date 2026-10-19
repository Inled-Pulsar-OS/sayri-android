package es.inled.sayri.api

import com.google.gson.Gson
import es.inled.sayri.SayriApp
import es.inled.sayri.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

class SayriApiClient(private val app: SayriApp = SayriApp.instance) {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(client)

    private fun newRequestBuilder(path: String): Request.Builder {
        val url = "${app.baseUrl}$path"
        val builder = Request.Builder().url(url)
        if (app.apiKey.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer ${app.apiKey}")
        }
        return builder
    }

    suspend fun getStatus(): Result<SayriStatusResponse> = withContext(Dispatchers.IO) {
        try {
            val req = newRequestBuilder("/api/v1/status").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}: ${resp.message}"))
                }
                val body = resp.body?.string() ?: ""
                val status = gson.fromJson(body, SayriStatusResponse::class.java)
                Result.success(status)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveIdea(ideaText: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = mapOf("idea" to ideaText)
            val reqBody = gson.toJson(payload).toRequestBody(jsonMediaType)
            val req = newRequestBuilder("/api/v1/memory/ideas").post(reqBody).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveTomorrowTask(taskText: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = mapOf("task" to taskText)
            val reqBody = gson.toJson(payload).toRequestBody(jsonMediaType)
            val req = newRequestBuilder("/api/v1/memory/tomorrow").post(reqBody).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendScreenSnapshot(snapshot: MobileScreenSnapshot): Result<String> = withContext(Dispatchers.IO) {
        try {
            val reqBody = gson.toJson(snapshot).toRequestBody(jsonMediaType)
            val req = newRequestBuilder("/api/v1/mobile/action").post(reqBody).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                }
                val body = resp.body?.string() ?: ""
                val actionResp = gson.fromJson(body, MobileActionResponse::class.java)
                Result.success(actionResp.decision ?: "Acción completada.")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun streamChat(
        messages: List<ChatRoleMessage>,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit
    ): EventSource {
        val requestObj = ChatCompletionRequest(
            model = "sayri",
            messages = messages,
            stream = true
        )
        val jsonPayload = gson.toJson(requestObj)
        val req = newRequestBuilder("/v1/chat/completions")
            .post(jsonPayload.toRequestBody(jsonMediaType))
            .addHeader("Accept", "text/event-stream")
            .build()

        val fullResponse = StringBuilder()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.trim() == "[DONE]") {
                    onDone(fullResponse.toString())
                    return
                }
                try {
                    val chunk = gson.fromJson(data, ChatChunk::class.java)
                    val deltaText = chunk.choices?.firstOrNull()?.delta?.content
                    if (!deltaText.isNullOrEmpty()) {
                        fullResponse.append(deltaText)
                        onDelta(deltaText)
                    }
                } catch (e: Exception) {
                    // Ignore JSON chunk parse error
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                onError(t ?: IOException("Error en stream HTTP ${response?.code}"))
            }

            override fun onClosed(eventSource: EventSource) {
                onDone(fullResponse.toString())
            }
        }

        return sseFactory.newEventSource(req, listener)
    }
}
