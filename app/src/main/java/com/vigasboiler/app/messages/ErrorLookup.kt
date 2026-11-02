package com.vigasboiler.app.messages

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.vigasboiler.app.feature.SelectionStore
import com.vigasboiler.app.net.ApiErrors
import com.vigasboiler.app.net.ViessmannApi
import com.vigasboiler.app.ratelimit.CallKind
import com.vigasboiler.app.ratelimit.CallPermission
import com.vigasboiler.app.ratelimit.RateLimitTracker

/**
 * A code explanation. [description] is the cause/meaning, [effect] what the boiler does,
 * [solutions] what to do. [source] names where the text comes from.
 */
data class ErrorInfo(
    val code: String,
    val description: String?,
    val solutions: List<String>,
    val raw: String,
    val effect: String? = null,
    val source: String? = null,
)

sealed interface LookupResult {
    data class Found(val info: ErrorInfo) : LookupResult
    data class NotFound(val code: String) : LookupResult
    data class Failed(val message: String) : LookupResult
}

/**
 * The response shape of `/service-documents/v3/error-database` is not documented in the
 * collection, so this parser looks for the object that carries the requested code and takes
 * description/solution-like fields from it. The raw JSON is always kept for display.
 */
object ErrorDatabaseParser {
    fun parse(code: String, body: String): ErrorInfo? {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
        val wanted = normalize(code)
        val match = objects(root).firstOrNull { obj ->
            obj.values.any { it is JsonPrimitive && it.isString && normalize(it.content) == wanted }
        } ?: return null
        val texts = match.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.let { k to it.content } }
        val description = texts.firstOrNull { (k, _) -> DESCRIPTION.any { k.contains(it, ignoreCase = true) } }?.second
        val solutions = match.entries.filter { (k, _) -> SOLUTION.any { k.contains(it, ignoreCase = true) } }.flatMap { (_, v) -> strings(v) }
        return ErrorInfo(code, description, solutions, body)
    }

    /** "s.154-1 " → "S.154-1"; also accepts "S154" and "s 29", so notations in the list still match. */
    fun normalize(code: String): String {
        val trimmed = code.trim().uppercase().replace(" ", "")
        val m = Regex("^([A-Z])\\.?([0-9]+(?:-[0-9]+)?)$").matchEntire(trimmed) ?: return trimmed
        return "${m.groupValues[1]}.${m.groupValues[2]}"
    }

    /** Structure only, for debug logs: entry count and field names, never values. */
    fun shape(body: String): String {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return "not JSON (${body.length} chars)"
        val all = objects(root).toList()
        val keys = all.flatMap { it.keys }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(15).joinToString { "${it.key}×${it.value}" }
        return "${all.size} objects; keys: $keys"
    }

    private fun objects(e: JsonElement): Sequence<JsonObject> = sequence {
        when (e) {
            is JsonObject -> {
                yield(e)
                e.values.forEach { yieldAll(objects(it)) }
            }
            is JsonArray -> e.forEach { yieldAll(objects(it)) }
            else -> Unit
        }
    }

    private fun strings(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> if (e.isString && e.content.isNotBlank()) listOf(e.content) else emptyList()
        is JsonArray -> e.flatMap(::strings)
        is JsonObject -> e.values.flatMap(::strings)
    }

    private val DESCRIPTION = listOf("description", "title", "text", "name", "meaning")
    private val SOLUTION = listOf("solution", "remed", "measure", "cause", "action")
}

private const val MISSING = "__404__"

class ErrorLookupRepository(
    private val api: ViessmannApi,
    private val tracker: RateLimitTracker,
    private val selectionStore: SelectionStore,
    private val discoveryDir: File,
    private val cacheDir: File,
    /** Debug log sink; the lines carry only the code and the outcome, no identifiers. */
    private val log: (String) -> Unit = {},
) {
    suspend fun lookup(code: String, languageCode: String = "en"): LookupResult =
        lookupInternal(code, languageCode).also { result ->
            log("lookup $code -> ${result.javaClass.simpleName}${(result as? LookupResult.Failed)?.let { ": " + it.message } ?: ""}")
        }

    private suspend fun lookupInternal(code: String, languageCode: String): LookupResult = withContext(Dispatchers.IO) {
        val material = materialNumber() ?: return@withContext LookupResult.Failed("No material number. Run discovery once.")
        val country = countryCode() ?: "DE"
        val safeCode = code.replace(Regex("[^A-Za-z0-9.]"), "_")

        // 1. The single code.
        when (val r = fetchCached("${material}_${safeCode}_$languageCode.json") { api.errorDatabase(material, code, country, languageCode) }) {
            is Fetched.Body -> ErrorDatabaseParser.parse(code, r.text)?.let { return@withContext found(it) }
            is Fetched.Error -> return@withContext LookupResult.Failed(r.message)
            Fetched.Missing -> Unit
        }
        // 2. The API answers 404 for codes it does not file under exactly this id; search the
        //    material's full list once (cached) with notation-tolerant matching.
        when (val r = fetchCached("${material}_all_$languageCode.json") { api.errorDatabase(material, null, country, languageCode) }) {
            is Fetched.Body -> {
                log("full list: ${ErrorDatabaseParser.shape(r.text)}")
                ErrorDatabaseParser.parse(code, r.text)?.let { found(it) } ?: LookupResult.NotFound(code)
            }
            is Fetched.Error -> LookupResult.Failed(r.message)
            Fetched.Missing -> LookupResult.NotFound(code)
        }
    }

    private fun found(info: ErrorInfo) = LookupResult.Found(info.copy(source = "Viessmann error database"))

    private sealed interface Fetched {
        data class Body(val text: String) : Fetched
        data object Missing : Fetched
        data class Error(val message: String) : Fetched
    }

    /** Cached GET: a 404 is cached too, so a missing code never costs a second call. */
    private suspend fun fetchCached(name: String, call: suspend () -> retrofit2.Response<okhttp3.ResponseBody>): Fetched {
        val cache = File(cacheDir, name)
        if (cache.exists()) {
            val text = cache.readText()
            return if (text == MISSING) Fetched.Missing else Fetched.Body(text)
        }
        if (tracker.check(CallKind.Manual) != CallPermission.Allowed) return Fetched.Error("API budget")
        val response = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Fetched.Error(e.message ?: e.javaClass.simpleName)
        }
        val text = (response.body() ?: response.errorBody())?.string().orEmpty()
        cacheDir.mkdirs()
        return when {
            response.isSuccessful -> Fetched.Body(text).also { cache.writeText(text) }
            response.code() == 404 -> Fetched.Missing.also { cache.writeText(MISSING) }
            else -> Fetched.Error(ApiErrors.readable(response.code(), text))
        }
    }

    /** `materialNumber` of the selected device, from the discovery dump's equipment files. */
    private suspend fun materialNumber(): String? {
        val deviceId = selectionStore.read()?.deviceId ?: "0"
        val files = discoveryDir.listFiles { f -> f.name.startsWith("04_devices") || f.name.startsWith("02_installations") }.orEmpty()
        return files.asSequence().mapNotNull { f -> runCatching { Json.parseToJsonElement(f.readText()) }.getOrNull() }
            .flatMap { root -> deviceObjects(root) }
            .firstOrNull { (it["id"] as? JsonPrimitive)?.content == deviceId && it["materialNumber"] != null }
            ?.let { (it["materialNumber"] as? JsonPrimitive)?.content }
    }

    private fun countryCode(): String? {
        val file = File(discoveryDir, "02_installations.json").takeIf { it.exists() } ?: return null
        val root = runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() ?: return null
        return deviceObjects(root).firstNotNullOfOrNull { (it["country"] as? JsonPrimitive)?.content }?.uppercase()
    }

    private fun deviceObjects(e: JsonElement): Sequence<JsonObject> = sequence {
        when (e) {
            is JsonObject -> {
                yield(e)
                e.values.forEach { yieldAll(deviceObjects(it)) }
            }
            is JsonArray -> e.forEach { yieldAll(deviceObjects(it)) }
            else -> Unit
        }
    }
}
