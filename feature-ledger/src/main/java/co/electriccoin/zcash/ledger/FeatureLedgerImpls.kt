package co.electriccoin.zcash.ledger

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.ledger.LedgerAccountImporter
import co.electriccoin.zcash.ui.common.ledger.LedgerNavContributor
import co.electriccoin.zcash.ui.common.ledger.LedgerNavigator
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentAccess
import co.electriccoin.zcash.ui.common.repository.LedgerPairingRepository
import co.electriccoin.zcash.ui.common.repository.LedgerRepairTargetRepository
import co.electriccoin.zcash.ui.common.usecase.CreateLedgerAccountUseCase
import co.electriccoin.zcash.ui.dialogComposable
import co.electriccoin.zcash.ui.screen.connectledger.connect.LedgerConnectArgs
import co.electriccoin.zcash.ui.screen.connectledger.connect.LedgerConnectScreen
import co.electriccoin.zcash.ui.screen.connecthw.CommittedEnrollmentBirthday
import co.electriccoin.zcash.ui.screen.connectledger.connected.LedgerConnectedArgs
import co.electriccoin.zcash.ui.screen.connectledger.connected.LedgerConnectedScreen
import co.electriccoin.zcash.ui.screen.connectledger.handshake.LedgerHandshakeArgs
import co.electriccoin.zcash.ui.screen.connectledger.handshake.LedgerHandshakeScreen
import co.electriccoin.zcash.ui.screen.connectledger.openapp.LedgerOpenAppArgs
import co.electriccoin.zcash.ui.screen.connectledger.openapp.LedgerOpenAppScreen
import co.electriccoin.zcash.ui.screen.connectledger.scan.LedgerDeviceScanArgs
import co.electriccoin.zcash.ui.screen.connectledger.scan.LedgerDeviceScanScreen
import co.electriccoin.zcash.ui.screen.connectledger.turnon.LedgerTurnOnArgs
import co.electriccoin.zcash.ui.screen.connectledger.turnon.LedgerTurnOnScreen
import co.electriccoin.zcash.ui.screen.signledgertransaction.LedgerSignArgs
import co.electriccoin.zcash.ui.screen.signledgertransaction.LedgerSignScreen
import kotlinx.coroutines.flow.combine

class LedgerAccountImporterImpl(
    private val ledgerPairingRepository: LedgerPairingRepository,
    private val createLedgerAccount: CreateLedgerAccountUseCase,
    private val walletEnrollment: WalletEnrollmentAccess,
) : LedgerAccountImporter {
    override val committedBirthdays =
        combine(ledgerPairingRepository.imported, walletEnrollment.ownershipChanges) { _, _ ->
            committedBirthday()
        }

    override fun committedBirthday() = currentPairing()
        ?.let(ledgerPairingRepository::importedAccount)
        ?.let { CommittedEnrollmentBirthday(it.birthday, it.account) }

    override fun hasPendingPairing(): Boolean = currentPairing() != null

    private fun currentPairing() = ledgerPairingRepository.get()?.takeIf { pairing ->
        ledgerPairingRepository.owner(pairing)?.let(walletEnrollment::permits) == true
    }

    override suspend fun importAccount(birthday: BlockHeight?, sourceEntryId: String?) =
        createLedgerAccount(birthday, sourceEntryId)
}

class LedgerNavigatorImpl(
    private val navigationRouter: NavigationRouter,
    private val ledgerRepairTargetRepository: LedgerRepairTargetRepository,
) : LedgerNavigator {
    override fun forwardToSign(sourceEntryId: String?, canSign: () -> Boolean) =
        navigationRouter.stageResultFrom(sourceEntryId, canSign) {
            NavigationCommand.Forward(listOf(LedgerSignArgs))
        }

    /**
     * Opens the connect flow to add a Ledger, so no account is left as the target of pairing again.
     */
    override fun forwardToConnect() {
        ledgerRepairTargetRepository.clear()
        navigationRouter.forward(LedgerConnectArgs)
    }

    override fun forwardToConnected() = navigationRouter.forward(LedgerConnectedArgs)
}

class LedgerNavContributorImpl : LedgerNavContributor {
    override fun contribute(navGraphBuilder: NavGraphBuilder) {
        with(navGraphBuilder) {
            dialogComposable<LedgerSignArgs> { LedgerSignScreen(it.id) }
            composable<LedgerConnectArgs> { LedgerConnectScreen() }
            composable<LedgerTurnOnArgs> { LedgerTurnOnScreen() }
            composable<LedgerDeviceScanArgs> { LedgerDeviceScanScreen(it.id) }
            composable<LedgerOpenAppArgs> { LedgerOpenAppScreen(it.toRoute(), it.id) }
            composable<LedgerHandshakeArgs> { LedgerHandshakeScreen(it.id) }
            composable<LedgerConnectedArgs> { LedgerConnectedScreen() }
        }
    }
}
