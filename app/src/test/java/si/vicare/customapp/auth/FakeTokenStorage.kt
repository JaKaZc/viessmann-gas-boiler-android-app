package si.vicare.customapp.auth

class FakeTokenStorage(var tokens: Tokens? = null) : TokenStorage {
    var writes = 0
    override suspend fun read(): Tokens? = tokens
    override suspend fun write(tokens: Tokens) {
        writes++
        this.tokens = tokens
    }
    override suspend fun clear() {
        tokens = null
    }
}

/** Counts refreshes and hands out "new-1", "new-2", … access tokens. */
class CountingRefreshClient(
    private val delayMillis: Long = 0,
    private val result: (Int) -> RefreshResult = { n -> RefreshResult.Success("new-$n", null, 3600) },
) : RefreshClient {
    @Volatile var calls = 0
    override suspend fun refresh(refreshToken: String): RefreshResult {
        val n = synchronized(this) { ++calls }
        if (delayMillis > 0) Thread.sleep(delayMillis) // block like a real network call
        return result(n)
    }
}
