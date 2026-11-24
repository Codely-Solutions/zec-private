package co.electriccoin.zcash.work

/** Stops work for one account while it is removed and rejects old jobs after successful deletion. */
internal object MigrationAccountRetirement {
    private enum class State { REMOVING, REMOVED }

    private val retired = mutableMapOf<String, State>()

    @Synchronized
    fun begin(accountKeyId: String) {
        check(accountKeyId !in retired) { "The migration account is already being removed" }
        retired[accountKeyId] = State.REMOVING
    }

    @Synchronized
    fun markRemoved(accountKeyId: String) {
        retired[accountKeyId] = State.REMOVED
    }

    @Synchronized
    fun restore(accountKeyId: String) {
        retired.remove(accountKeyId)
    }

    @Synchronized
    fun blocks(accountKeyId: String): Boolean = accountKeyId in retired

    @Synchronized
    fun <T> admit(accountKeyId: String, block: () -> T): T? =
        if (accountKeyId in retired) null else block()

    @Synchronized
    fun isRemoved(accountKeyId: String): Boolean = retired[accountKeyId] == State.REMOVED

    /** Keep WorkManager and its fallback alarm durable while removal can still fail. */
    @Synchronized
    fun <T> keepWake(accountKeyId: String, block: () -> T): T? =
        if (isRemoved(accountKeyId)) null else block()
}
