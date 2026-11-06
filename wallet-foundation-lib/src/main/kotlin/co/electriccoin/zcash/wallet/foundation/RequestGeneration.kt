package co.electriccoin.zcash.wallet.foundation

/** Opaque identity of one request within its owning [RequestGeneration]. */
class RequestId internal constructor(internal val owner: RequestGeneration, internal val generation: Long)

/**
 * Invalidates previous requests when a replacement starts or its result is cleared.
 * Callers must synchronize access, including the current-request check and its resulting state update,
 * under the same lock. This class does not cancel work or publish results.
 */
class RequestGeneration {
    private var generation = 0L

    fun advance(): RequestId {
        check(generation < Long.MAX_VALUE) { "Request generation exhausted" }
        generation += 1
        return RequestId(this, generation)
    }

    fun isCurrent(request: RequestId): Boolean = request.owner === this && request.generation == generation
}
