package app.grammarfloat.pro.api

import okhttp3.Response
import kotlinx.coroutines.CancellationException

sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidKey(provider: String) : ApiException("Invalid $provider API key")
    class RateLimited : ApiException("Rate limit hit — try again shortly")
    class NetworkError(cause: Throwable) : ApiException("Network error: ${cause.message}", cause)
    class ParseError(message: String = "Unexpected response format") : ApiException(message)
    class GeneralError(message: String) : ApiException(message)
    class Timeout(cause: Throwable) : ApiException("Request timed out — please try again", cause)
}

suspend inline fun <T> safeApiCall(provider: String, crossinline block: suspend () -> T): T {
    return try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: java.net.SocketTimeoutException) {
        throw ApiException.Timeout(e)
    } catch (e: java.io.InterruptedIOException) {
        if (e.message?.contains("timeout", ignoreCase = true) == true) {
            throw ApiException.Timeout(e)
        } else {
            throw ApiException.NetworkError(e)
        }
    } catch (e: Exception) {
        throw ApiException.NetworkError(e)
    }
}

fun Response.checkStatus(provider: String) {
    if (!isSuccessful) {
        val errorBody = try { body.string() } catch (e: Exception) { "" }
        android.util.Log.e("ApiException", "$provider API error ($code): $errorBody")
        throw when (code) {
            401 -> ApiException.InvalidKey(provider)
            429 -> ApiException.RateLimited()
            500, 503 -> ApiException.GeneralError("The AI service is temporarily unavailable.")
            else -> ApiException.GeneralError("API error $code${if (errorBody.isNotEmpty()) ": $errorBody" else ""}")
        }
    }
}
