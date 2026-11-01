package si.vicare.customapp.net

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Debug-only request log. Logs method, a redacted path, status and duration. Never logs headers
 * (so no Authorization), bodies (which can carry tokens) or query values.
 */
class RedactingLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val start = System.nanoTime()
        val line = "${request.method} ${redactedPath(request.url)}"
        return try {
            val response = chain.proceed(request)
            Log.d(TAG, "$line -> ${response.code} (${(System.nanoTime() - start) / 1_000_000} ms)")
            response
        } catch (e: Exception) {
            Log.d(TAG, "$line -> ${e.javaClass.simpleName}")
            throw e
        }
    }

    companion object {
        private const val TAG = "ViessmannHttp"

        /** Replaces installation IDs, gateway serials and other identifier-like segments with `***`. */
        fun redactedPath(url: HttpUrl): String =
            url.pathSegments.joinToString("/", prefix = "/") { segment ->
                if (segment.any(Char::isDigit) && segment.length >= 4) "***" else segment
            } + if (url.querySize > 0) "?" + url.queryParameterNames.joinToString("&") { "$it=…" } else ""
    }
}
