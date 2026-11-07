package co.electriccoin.zcash.ui.common.donation

import android.content.Context
import co.electriccoin.zcash.ui.common.model.VersionInfo
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID
import java.security.MessageDigest

/** A new authorization is deliberately independent of the legacy suggestion preference. */
data class AutoDonationConsent(
    val id: String,
    val accountUuid: String,
    val percent: BigDecimal,
    val network: String,
    val authorizedAtSeconds: Long,
)

class AutoDonationStore(context: Context) {
    private companion object {
        val journalLock = Any()
        val MAX_PERCENT = BigDecimal(100)
        const val MILLIS_PER_SECOND = 1000L
    }
    private val prefs = context.getSharedPreferences("private_auto_donation_v1", Context.MODE_PRIVATE)

    @Synchronized fun consent(): AutoDonationConsent? = runCatching {
        val json = JSONObject(prefs.getString("consent", null) ?: return null)
        AutoDonationConsent(json.getString("id"), json.getString("account"),
            json.getString("percent").toBigDecimal(), json.getString("network"), json.getLong("authorizedAt"))
            .takeIf { it.network == VersionInfo.NETWORK_DIMENSION.name && it.percent > BigDecimal.ZERO &&
                it.percent <= MAX_PERCENT }
    }.getOrNull()

    /** Caller must obtain fresh explicit authorization for this account and percentage first. */
    @Synchronized fun authorize(accountUuid: String, percent: BigDecimal): AutoDonationConsent {
        require(accountUuid.isNotBlank() && percent > BigDecimal.ZERO && percent <= MAX_PERCENT)
        val value = AutoDonationConsent(UUID.randomUUID().toString(), accountUuid, percent,
            VersionInfo.NETWORK_DIMENSION.name, System.currentTimeMillis() / MILLIS_PER_SECOND)
        check(prefs.edit().putString("consent", JSONObject().put("id", value.id).put("account", accountUuid)
            .put("percent", percent.toPlainString()).put("network", value.network)
            .put("authorizedAt", value.authorizedAtSeconds).toString()).commit())
        return value
    }

    @Synchronized fun disable() { check(prefs.edit().remove("consent").commit()) }
    fun latestOutcome(): String? = prefs.getString("outcome", null)

    internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    internal fun enroll(key: String, enrollment: JSONObject) = synchronized(journalLock) {
        if (!prefs.contains("swap:${hash(key)}")) {
            check(prefs.edit().putString("swap:${hash(key)}", enrollment.toString()).commit())
        }
    }
    internal fun reserve(key: String): JSONObject? = synchronized(journalLock) {
        val value = prefs.getString("swap:${hash(key)}", null)?.let(::JSONObject) ?: return@synchronized null
        if (value.optBoolean("consumed")) return@synchronized null
        value.put("consumed", true)
        // Persist before launching work. A crash leaves this swap consumed, never payable again.
        check(prefs.edit().putString("swap:${hash(key)}", value.toString()).commit())
        value
    }
    internal fun recordTransactionIds(key: String, ids: List<String>) = synchronized(journalLock) {
        check(prefs.edit().putString("transactions:${hash(key)}", org.json.JSONArray(ids).toString()).commit())
    }

    @Synchronized internal fun outcome(key: String, reason: String) {
        check(prefs.edit().putString("outcome", reason).putString("result:${hash(key)}", reason).commit())
    }
}
