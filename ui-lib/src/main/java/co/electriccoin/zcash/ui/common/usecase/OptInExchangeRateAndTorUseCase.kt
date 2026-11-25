package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.global.WalletSdkTransportGate
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.isForegroundAtEntry
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class OptInExchangeRateAndTorUseCase(
    private val navigationRouter: NavigationRouter,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val appAccessSession: AppAccessSession,
    private val walletWriteAccess: WalletWriteAccess,
    private val transportGate: WalletSdkTransportGate = WalletSdkTransportGate(),
    private val walletTransport: WalletTransportAccess = WalletTransportAccess(),
    private val giftCards: GiftCardDataSource? = null,
) {
    suspend operator fun invoke(optIn: Boolean, sourceEntryId: String? = null) {
        val grant = appAccessSession.grant.value
        val generation = walletWriteAccess.token()
        val canSave = { appAccessSession.permits(grant) && walletWriteAccess.permits(generation) }
        val saved = walletTransport.ifIdleSwitch {
            if (giftCards?.canChangeTorMode() == false) throw TorChangeWalletBusyException()
            walletWriteAccess.withSdkRecovery(generation) {
                walletWriteAccess.write(generation) {
                    if (!canSave() || !navigationRouter.isForegroundAtEntry(sourceEntryId)) return@write false
                    withContext(NonCancellable) {
                        transportGate.whileSavingChoice {
                            runCatchingRecoverable { isTorEnabledStorageProvider.store(optIn) }.getOrElse {
                                error("Could not save the Tor protection setting")
                            }
                            transportGate.recordCommittedMode(optIn)
                            !transportGate.restartRequired.value
                        }
                    }
                }
            } == true
        } ?: throw TorChangeWalletBusyException()
        if (saved) {
            navigationRouter.stageResultFrom(sourceEntryId, canSave) {
                NavigationCommand.Back
            }
        }
    }
}

internal class TorChangeWalletBusyException : IllegalStateException("A wallet operation is still running")
