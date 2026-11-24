package co.electriccoin.zcash.migration

/**
 * App-owned migration diagnostics use the debug-only MIGRATION tag. Log phases, counts and
 * failure categories. Keep account and transaction identities, transfer schedules, and raw
 * SDK errors in their owning components.
 */
internal fun migrationLog(message: String) {
    if (BuildConfig.DEBUG) android.util.Log.d("MIGRATION", message)
}
