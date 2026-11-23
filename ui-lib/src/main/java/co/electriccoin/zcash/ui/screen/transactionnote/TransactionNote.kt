package co.electriccoin.zcash.ui.screen.transactionnote

import cash.z.ecc.android.sdk.model.AccountUuid
import kotlinx.serialization.Serializable

@Serializable
data class TransactionNote(
    val txId: String,
    val accountKeyId: String? = null,
    val walletWriteToken: Long? = null,
) {
    @OptIn(ExperimentalStdlibApi::class)
    internal fun accountUuid(): AccountUuid? = accountKeyId?.let { AccountUuid.new(it.hexToByteArray()) }
}
