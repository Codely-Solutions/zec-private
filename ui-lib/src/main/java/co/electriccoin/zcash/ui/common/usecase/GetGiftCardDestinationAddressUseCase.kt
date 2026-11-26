package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.UnifiedAddressRequest
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.GiftCardException
import co.electriccoin.zcash.ui.common.model.GiftRedemptionDestination
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess

/**
 * Resolves the Orchard-only unified address of the account reviewed for gift redemption.
 */
class GetGiftCardDestinationAddressUseCase(
    private val synchronizerProvider: SynchronizerProvider,
    private val appAccess: AppAccessSession,
    private val walletAccess: WalletWriteAccess,
    private val walletTransport: WalletTransportAccess,
) {
    operator fun invoke(account: WalletAccount): suspend () -> GiftRedemptionDestination {
        val grant = appAccess.grant.value
        val generation = walletAccess.token()
        val engine = synchronizerProvider.synchronizer.value
        val permits = {
            engine != null && appAccess.permits(grant) && walletAccess.permits(generation) &&
                !walletTransport.isRetired(engine) && synchronizerProvider.synchronizer.value === engine
        }
        return {
            if (engine == null || !permits()) throw GiftCardException.SubmitFailed()
            val address = engine.getCustomUnifiedAddress(account.sdkAccount, UnifiedAddressRequest.Orchard)
            if (!permits()) throw GiftCardException.SubmitFailed()
            GiftRedemptionDestination(address, permits)
        }
    }
}
