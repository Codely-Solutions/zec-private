package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.UnifiedAddressRequest
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider

/**
 * Resolves the Orchard-only unified address of the account reviewed for gift redemption.
 */
class GetGiftCardDestinationAddressUseCase(
    private val synchronizerProvider: SynchronizerProvider,
) {
    suspend operator fun invoke(account: WalletAccount): String =
        synchronizerProvider
            .getSynchronizer()
            .getCustomUnifiedAddress(account.sdkAccount, UnifiedAddressRequest.Orchard)
}
