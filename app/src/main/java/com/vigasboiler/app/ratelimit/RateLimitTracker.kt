package com.vigasboiler.app.ratelimit

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Timestamps of every API call (sliding windows are computed from these). */
interface ApiCallLog {
    suspend fun record(timestampMillis: Long)
    suspend fun countSince(sinceMillis: Long): Int
    suspend fun pruneBefore(beforeMillis: Long)
}

/** Persists a block reported by the API (429) so it survives app restarts. */
interface RateLimitBlockStore {
    suspend fun read(): RateLimitBlock?
    suspend fun write(block: RateLimitBlock?)
}

data class RateLimitBlock(val untilMillis: Long, val limitName: String?)

data class RateBudget(
    val used10m: Int,
    val used24h: Int,
    val block: RateLimitBlock?,
    val limit10m: Int = RateLimitTracker.LIMIT_10M,
    val limit24h: Int = RateLimitTracker.LIMIT_24H,
) {
    val remaining10m: Int get() = (limit10m - used10m).coerceAtLeast(0)
    val remaining24h: Int get() = (limit24h - used24h).coerceAtLeast(0)

    /** Highest usage of the two windows, 0.0 … 1.0+. */
    val usage: Double get() = maxOf(used10m.toDouble() / limit10m, used24h.toDouble() / limit24h)
    val isSoftLimited: Boolean get() = usage >= RateLimitTracker.SOFT_LIMIT
}

/** Who wants to call the API. Only explicit user actions may pass the 80 % soft limit. */
enum class CallKind { Background, Automatic, Manual, Command }

sealed interface CallPermission {
    data object Allowed : CallPermission

    /** The API returned 429; everything waits until [block] ends. */
    data class Blocked(val block: RateLimitBlock) : CallPermission

    /** ≥ 80 % of a window used; only manual refreshes and commands are allowed. */
    data class SoftLimited(val budget: RateBudget) : CallPermission

    /** The planned calls would hit a hard limit, which would lock the account (and the ViCare app). */
    data class WouldExceed(val budget: RateBudget) : CallPermission
}

/** Thrown by the interceptor instead of sending a request while a 429 block is active. */
class RateLimitedException(val block: RateLimitBlock) : IOException("Rate limited until ${block.untilMillis}")

/**
 * Local bookkeeping of the Basic plan limits: 120 calls per 10 minutes and 1450 per 24 hours,
 * both sliding windows.
 */
class RateLimitTracker(
    private val log: ApiCallLog,
    private val blockStore: RateLimitBlockStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _budget = MutableStateFlow(RateBudget(0, 0, null))
    val budget: StateFlow<RateBudget> = _budget.asStateFlow()

    suspend fun currentBudget(): RateBudget = mutex.withLock { computeLocked() }

    /** Decides whether [calls] API calls of the given [kind] may be made now. */
    suspend fun check(kind: CallKind, calls: Int = 1): CallPermission = mutex.withLock {
        val budget = computeLocked()
        val block = budget.block
        when {
            block != null -> CallPermission.Blocked(block)
            budget.used10m + calls > budget.limit10m || budget.used24h + calls > budget.limit24h ->
                CallPermission.WouldExceed(budget)
            budget.isSoftLimited && kind != CallKind.Manual && kind != CallKind.Command ->
                CallPermission.SoftLimited(budget)
            else -> CallPermission.Allowed
        }
    }

    /** The active block, if any. Expired blocks are cleared. */
    suspend fun activeBlock(): RateLimitBlock? = mutex.withLock { activeBlockLocked() }

    /** Records one call. Called for every request, including ones that fail. */
    suspend fun onCall() = mutex.withLock {
        val now = clock()
        log.record(now)
        log.pruneBefore(now - WINDOW_24H)
        computeLocked()
        Unit
    }

    /** Stores the block described by a 429 response body. */
    suspend fun onRateLimited(body: String?) = mutex.withLock {
        blockStore.write(RateLimitErrorParser.parse(body, clock()))
        computeLocked()
        Unit
    }

    private suspend fun activeBlockLocked(): RateLimitBlock? {
        val block = blockStore.read() ?: return null
        if (block.untilMillis <= clock()) {
            blockStore.write(null)
            return null
        }
        return block
    }

    private suspend fun computeLocked(): RateBudget {
        val now = clock()
        return RateBudget(
            used10m = log.countSince(now - WINDOW_10M),
            used24h = log.countSince(now - WINDOW_24H),
            block = activeBlockLocked(),
        ).also { _budget.value = it }
    }

    companion object {
        const val LIMIT_10M = 120
        const val LIMIT_24H = 1450
        const val SOFT_LIMIT = 0.8
        const val WINDOW_10M = 10 * 60_000L
        const val WINDOW_24H = 24 * 60 * 60_000L
    }
}

/**
 * Reads the 429 body, e.g.
 * `{"errorType":"RATE_LIMIT_EXCEEDED","extendedPayload":{"name":"ViCare day limit","requestCountLimit":1450,"limitReset":1584462010106}}`.
 * Without `limitReset` the block lasts one full window of the named limit (10 minutes if unknown).
 */
object RateLimitErrorParser {
    fun parse(body: String?, nowMillis: Long): RateLimitBlock {
        val payload = body?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.get("extendedPayload") as? JsonObject
        val name = (payload?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val reset = (payload?.get("limitReset") as? JsonPrimitive)?.longOrNull?.takeIf { it > nowMillis }
        val fallbackWindow = when {
            name?.contains("day", ignoreCase = true) == true -> RateLimitTracker.WINDOW_24H
            (payload?.get("requestCountLimit") as? JsonPrimitive)?.longOrNull == RateLimitTracker.LIMIT_24H.toLong() -> RateLimitTracker.WINDOW_24H
            else -> RateLimitTracker.WINDOW_10M
        }
        return RateLimitBlock(reset ?: (nowMillis + fallbackWindow), name)
    }
}
