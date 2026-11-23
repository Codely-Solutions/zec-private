package co.electriccoin.zcash.ui.screen.connecthw

import android.net.Uri
import android.os.Bundle
import androidx.navigation.NavType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.reflect.typeOf

/**
 * Identifies the enrollment using the shared birthday screens. Keystone carries an opaque ID for
 * its process-local session. The Ledger and view-only features keep their pending data in memory.
 * View-only enrollment goes directly to the birthday question, then to the account name.
 */
@Serializable
sealed interface HWWalletEnrollment {
    @Serializable
    data class Keystone(
        val sessionId: String
    ) : HWWalletEnrollment

    @Serializable
    data object Ledger : HWWalletEnrollment

    @Serializable
    data object ViewOnly : HWWalletEnrollment
}

/**
 * Lets [HWWalletEnrollment] travel as a type-safe navigation argument. Navigation derives
 * types for primitives and enums on its own, but a sealed type needs an explicit [NavType]; the
 * value rides as its JSON encoding, URL-encoded because it becomes a route path segment.
 */
object HWWalletEnrollmentNavType : NavType<HWWalletEnrollment>(isNullableAllowed = false) {
    val typeMap = mapOf(typeOf<HWWalletEnrollment>() to this)

    override fun put(
        bundle: Bundle,
        key: String,
        value: HWWalletEnrollment
    ) = bundle.putString(key, Json.encodeToString(value))

    override fun get(
        bundle: Bundle,
        key: String
    ): HWWalletEnrollment? = bundle.getString(key)?.let { Json.decodeFromString(it) }

    override fun parseValue(value: String): HWWalletEnrollment = Json.decodeFromString(value)

    override fun serializeAsValue(value: HWWalletEnrollment): String = Uri.encode(Json.encodeToString(value))
}
