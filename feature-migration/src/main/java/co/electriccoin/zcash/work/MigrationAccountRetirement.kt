package co.electriccoin.zcash.work

/** Stops work for one account while it is removed and rejects old jobs after successful deletion. */
internal object MigrationAccountRetirement {
    private val retired = mutableSetOf<String>()

    @Synchronized
    fun begin(accountKeyId: String) {
        check(retired.add(accountKeyId)) { "The migration account is already being removed" }
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
}
