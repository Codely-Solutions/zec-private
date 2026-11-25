package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSource
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerNavigator
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShieldFundsUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val ledgerNavigator: LedgerNavigator,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val navigationRouter: NavigationRouter,
    private val accountDataSource: AccountDataSource,
    private val navigateToError: NavigateToErrorUseCase,
    private val messageAvailabilityDataSource: MessageAvailabilityDataSource,
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var job: Job? = null

    operator fun invoke(closeCurrentScreen: Boolean) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                messageAvailabilityDataSource.onShieldingInitiated()

                when (accountDataSource.getSelectedAccount()) {
                    is KeystoneAccount -> {
                        createKeystoneShieldProposal()
                    }

                    is LedgerAccount -> {
                        createLedgerShieldProposal()
                    }

                    is ViewOnlyAccount -> {
                        navigateToError(
                            ErrorArgs.General(
                                AccountOperationUnsupportedException.viewOnly()
                            )
                        )
                    }

                    is ZodlAccount -> {
                        if (closeCurrentScreen) {
                            navigationRouter.back()
                        }
                        shieldZashiFunds()
                    }
                }
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun shieldZashiFunds() {
        var created: ShieldTransactionProposal? = null
        try {
            val proposal = zashiProposalRepository.createShieldProposal().also { created = it }
            when (val result = zashiProposalRepository.submit(proposal)) {
                is SubmitResult.Failure,
                is SubmitResult.GrpcFailure,
                is SubmitResult.Error,
                is SubmitResult.Partial -> {
                    navigateToError(ErrorArgs.ShieldingError(result))
                }

                is SubmitResult.Success -> {
                    // do nothing
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            navigateToError(ErrorArgs.ShieldingGeneralError(e))
        } finally {
            created?.let(zashiProposalRepository::clear)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun createKeystoneShieldProposal() {
        var created: ShieldTransactionProposal? = null
        try {
            val proposal = keystoneProposalRepository.createShieldProposal().also { created = it }
            keystoneProposalRepository.createPCZTFromProposal()
            keystoneProposalRepository.approveForSigning(proposal)
            navigationRouter.forward(SignKeystoneTransactionArgs)
        } catch (e: CancellationException) {
            clearOwnedShield(
                created, keystoneProposalRepository.transactionProposal.value, keystoneProposalRepository::clear
            )
            throw e
        } catch (e: Exception) {
            clearOwnedShield(
                created, keystoneProposalRepository.transactionProposal.value, keystoneProposalRepository::clear
            )
            navigateToError(ErrorArgs.ShieldingGeneralError(e))
        }
    }

    /**
     * A cancelled shield drops its half-made proposal, as nothing but the sign sheet would hold it,
     * and propagates the cancellation instead of reporting it as a shielding error.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun createLedgerShieldProposal() {
        var created: ShieldTransactionProposal? = null
        try {
            val proposal = ledgerProposalPipeline.createShieldProposal().also { created = it }
            ledgerProposalPipeline.createPCZTFromProposal()
            ledgerProposalPipeline.approveForSigning(proposal)
            ledgerNavigator.forwardToSign()
        } catch (e: CancellationException) {
            clearOwnedShield(
                created, ledgerProposalPipeline.transactionProposal.value, ledgerProposalPipeline::clear
            )
            throw e
        } catch (e: Exception) {
            clearOwnedShield(
                created, ledgerProposalPipeline.transactionProposal.value, ledgerProposalPipeline::clear
            )
            navigateToError(ErrorArgs.ShieldingGeneralError(e))
        }
    }
}

private fun clearOwnedShield(
    created: ShieldTransactionProposal?,
    current: TransactionProposal?,
    clear: () -> Unit,
) {
    if (created != null && current === created) clear()
}
