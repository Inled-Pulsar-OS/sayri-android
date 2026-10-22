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

    suspend fun pairWithPin(pin: String, deviceName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = mapOf(
                "pin" to pin.trim(),
                "device_name" to deviceName,
                "device_id" to android.os.Build.FINGERPRINT
            )
            val reqBody = gson.toJson(payload).toRequestBody(jsonMediaType)
            val req = Request.Builder()
                .url("${app.baseUrl}/api/v1/pair")
                .post(reqBody)
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val errMsg = try {
                        val map = gson.fromJson(body, Map::class.java)
                        val errObj = map["error"] as? Map<*, *>
                        errObj?.get("message")?.toString() ?: "PIN incorrecto o caducado"
                    } catch (e: Exception) {
                        "HTTP ${resp.code}: PIN incorrecto"
                    }
                    return@withContext Result.failure(IOException(errMsg))
                }
                val map = gson.fromJson(body, Map::class.java)
                val token = map["token"]?.toString() ?: ""
                if (token.isNotEmpty()) {
                    app.apiKey = token
                    Result.success(token)
                } else {
                    Result.failure(IOException("No se recibió token de autorización"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun transcribeAudio(wavBytes: ByteArray, language: String = "auto"): Result<String> = withContext(Dispatchers.IO) {
        try {
            val audioMediaType = "audio/wav".toMediaType()
            val reqBody = wavBytes.toRequestBody(audioMediaType)
            val req = newRequestBuilder("/v1/audio/transcriptions")
                .post(reqBody)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}: ${resp.message}"))
                }
                val body = resp.body?.string() ?: ""
                val map = gson.fromJson(body, Map::class.java)
                val text = map["text"]?.toString()?.trim() ?: ""
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSessions(): Result<List<SessionSummary>> = withContext(Dispatchers.IO) {
        try {
            val req = newRequestBuilder("/api/v1/sessions").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}: ${resp.message}"))
                }
                val body = resp.body?.string() ?: ""
                val res = gson.fromJson(body, SessionListResponse::class.java)
                Result.success(res.sessions ?: emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSessionById(sessionId: String): Result<SessionDetail> = withContext(Dispatchers.IO) {
        try {
            val req = newRequestBuilder("/api/v1/sessions/$sessionId").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}: ${resp.message}"))
                }
                val body = resp.body?.string() ?: ""
                val res = gson.fromJson(body, SessionDetailResponse::class.java)
                Result.success(res.session)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createSession(title: String, customSessionId: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = mutableMapOf<String, Any>("title" to title)
            if (!customSessionId.isNullOrEmpty()) {
                payload["session_id"] = customSessionId
            }
            val reqBody = gson.toJson(payload).toRequestBody(jsonMediaType)
            val req = newRequestBuilder("/api/v1/sessions").post(reqBody).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}: ${resp.message}"))
                }
                val body = resp.body?.string() ?: ""
                val map = gson.fromJson(body, Map::class.java)
                val sid = map["session_id"]?.toString() ?: ""
                Result.success(sid)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteSession(sessionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val req = newRequestBuilder("/api/v1/sessions/$sessionId").delete().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}: ${resp.message}"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun streamChat(
        messages: List<ChatRoleMessage>,
        sessionId: String? = null,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit
    ): EventSource {
        val requestObj = ChatCompletionRequest(
            model = "sayri",
            messages = messages,
            stream = true,
            sessionId = sessionId
        )
        val jsonPayload = gson.toJson(requestObj)
        val reqBuilder = newRequestBuilder("/v1/chat/completions")
            .post(jsonPayload.toRequestBody(jsonMediaType))
            .addHeader("Accept", "text/event-stream")

        if (!sessionId.isNullOrEmpty()) {
            reqBuilder.addHeader("X-Session-ID", sessionId)
        }

        val req = reqBuilder.build()
        val fullResponse = StringBuilder()
        var finished = false

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.trim() == "[DONE]") {
                    if (!finished) {
                        finished = true
                        onDone(fullResponse.toString())
                    }
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
                if (!finished) {
                    finished = true
                    onError(t ?: IOException("Error en stream HTTP ${response?.code}"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!finished) {
                    finished = true
                    onDone(fullResponse.toString())
                }
            }
        }

        return sseFactory.newEventSource(req, listener)
    }
}
