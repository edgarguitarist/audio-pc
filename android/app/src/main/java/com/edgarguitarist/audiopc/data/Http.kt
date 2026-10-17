package com.edgarguitarist.audiopc.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json as KJson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared JSON config: servers may add fields in later API versions. */
val Json = KJson { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** The server answered with a non-2xx status. [code] is the API error code when the body had one. */
class ApiException(val status: Int, val code: String?, message: String?) : Exception(message ?: code ?: "HTTP $status")

class NoServerException : Exception("No server")

fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .addInterceptor { chain ->
        // The server localizes derived names/details by Accept-Language (API.md 6.2).
        chain.proceed(chain.request().newBuilder().header("Accept-Language", Locale.getDefault().toLanguageTag()).build())
    }
    .build()

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}

/** Outcome of one HTTP exchange, before interpreting it. */
sealed interface Raw {
    class Ok(val body: String) : Raw
    class Http(val status: Int, val error: ApiError?) : Raw
    class Net(val cause: IOException) : Raw
}

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

// The body is read after await() resumes, so pin the whole exchange to IO: callers run on
// Main (view models, widgets) and reading on Main throws NetworkOnMainThreadException.
suspend fun OkHttpClient.exchange(baseUrl: String, token: String?, method: String, path: String, body: String?): Raw =
    withContext(Dispatchers.IO) { exchangeBlocking(baseUrl, token, method, path, body) }

private suspend fun OkHttpClient.exchangeBlocking(baseUrl: String, token: String?, method: String, path: String, body: String?): Raw =
    try {
        val req = Request.Builder().url(baseUrl + path).apply {
            if (token != null) header("Authorization", "Bearer $token")
            when (method) {
                "GET" -> get()
                else -> method(method, (body ?: "{}").toRequestBody(JSON_TYPE))
            }
        }.build()
        newCall(req).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) Raw.Ok(text)
            else Raw.Http(r.code, runCatching { Json.decodeFromString<ApiError>(text) }.getOrNull())
        }
    } catch (e: IOException) {
        Raw.Net(e)
    } catch (e: IllegalArgumentException) {
        Raw.Net(IOException(e)) // malformed host typed by hand
    }

fun Raw.toResult(): Result<String> = when (this) {
    is Raw.Ok -> Result.success(body)
    is Raw.Http -> Result.failure(ApiException(status, error?.error, error?.message))
    is Raw.Net -> Result.failure(cause)
}
