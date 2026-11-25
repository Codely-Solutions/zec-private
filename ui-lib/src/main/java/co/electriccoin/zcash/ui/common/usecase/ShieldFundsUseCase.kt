package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.whileAtEntry
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSource
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldingApproval
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerNavigator
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
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
    private val walletWriteAccess: WalletWriteAccess,
    private val appAccessSession: AppAccessSession,
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var job: Job? = null

    fun confirm(account: WalletAccount, closeCurrentScreen: Boolean, sourceEntryId: String? = null): () -> Unit {
        val wallet = walletWriteAccess.token()
        return {
            val grant = appAccessSession.grant.value
            shield(account, closeCurrentScreen, sourceEntryId) {
                appAccessSession.permits(grant) && walletWriteAccess.permits(wallet)
            }
        }
    }

    private fun canStartShield(approval: ShieldingApproval, sourceEntryId: String?): Boolean =
        navigationRouter.isAtEntry(sourceEntryId) &&
            if (approval.account is ViewOnlyAccount) approval.isCurrent() else approval.isReadyToPropose()

    private fun shield(
        account: WalletAccount,
        closeCurrentScreen: Boolean,
        sourceEntryId: String?,
        sameSession: () -> Boolean,
    ) {
        if (job?.isActive == true || !sameSession() || !navigationRouter.isAtEntry(sourceEntryId)) return
        val outcomeEntryId =
            if (closeCurrentScreen && sourceEntryId != null) {
                navigationRouter.activeEntry.value?.contentEntryId ?: sourceEntryId
            } else {
                sourceEntryId
            }
        job =
            scope.launch {
                val selection = accountDataSource.confirmSelection(account) ?: return@launch
                val approval = newApproval(account) { sameSession() && selection() }
                if (!canStartShield(approval, sourceEntryId)) return@launch
                messageAvailabilityDataSource.onShieldingInitiated()

                if (!canStartShield(approval, sourceEntryId)) return@launch
                when (approval.account) {
                    is KeystoneAccount -> {
                        navigationRouter.whileAtEntry(sourceEntryId) {
                            createKeystoneShieldProposal(approval, sourceEntryId)
                        }
                    }

                    is LedgerAccount -> {
                        navigationRouter.whileAtEntry(sourceEntryId) {
                            createLedgerShieldProposal(approval, sourceEntryId)
                        }
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
                            navigationRouter.stageResultFrom(sourceEntryId) { NavigationCommand.Back }
                        }
                        shieldZashiFunds(approval, outcomeEntryId)
                    }
                }
            }
    }

    private fun newApproval(account: WalletAccount, isCurrent: () -> Boolean): ShieldingApproval =
        ShieldingApproval(
            account = account,
            hasShieldableBalance = {
                accountDataSource.allAccounts.value?.firstOrNull { it.isSelected }?.let {
                    it.sdkAccount.accountUuid == account.sdkAccount.accountUuid && it.isShieldingAvailable == true
                } == true
            },
            remainsCurrent = isCurrent,
        )

    @Suppress("TooGenericExceptionCaught")
    private suspend fun shieldZashiFunds(approval: ShieldingApproval, outcomeEntryId: String?) {
        try {
            val proposal = zashiProposalRepository.createShieldProposal(approval)
            when (val result = zashiProposalRepository.submit(proposal)) {
                is SubmitResult.Failure,
                is SubmitResult.GrpcFailure,
                is SubmitResult.Error,
                is SubmitResult.Partial -> {
                    showShieldError(ErrorArgs.ShieldingError(result), approval, outcomeEntryId)
                }

                is SubmitResult.Success -> {
                    // do nothing
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showShieldError(ErrorArgs.ShieldingGeneralError(e), approval, outcomeEntryId)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun createKeystoneShieldProposal(approval: ShieldingApproval, sourceEntryId: String?) {
        var created: ShieldTransactionProposal? = null
        try {
            val proposal = keystoneProposalRepository.createShieldProposal(approval).also { created = it }
            keystoneProposalRepository.createPCZTFromProposal(approval.account)
            approval.requireCurrent()
            keystoneProposalRepository.approveForSigning(proposal)
            navigationRouter.stageResultFrom(sourceEntryId, {
                approval.isCurrent() && keystoneProposalRepository.transactionProposal.value === proposal
            }) {
                NavigationCommand.Forward(listOf(SignKeystoneTransactionArgs))
            }
        } catch (e: CancellationException) {
            clearOwnedShield(
                created, { keystoneProposalRepository.transactionProposal.value }, keystoneProposalRepository::clear
            )
            throw e
        } catch (e: Exception) {
            clearOwnedShield(
                created, { keystoneProposalRepository.transactionProposal.value }, keystoneProposalRepository::clear
            )
            showShieldError(ErrorArgs.ShieldingGeneralError(e), approval, sourceEntryId)
        }
    }

    /**
     * A cancelled shield drops its half-made proposal, as nothing but the sign sheet would hold it,
     * and propagates the cancellation instead of reporting it as a shielding error.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun createLedgerShieldProposal(approval: ShieldingApproval, sourceEntryId: String?) {
        var created: ShieldTransactionProposal? = null
        try {
            val proposal = ledgerProposalPipeline.createShieldProposal(approval).also { created = it }
            ledgerProposalPipeline.createPCZTFromProposal()
            approval.requireCurrent()
            ledgerProposalPipeline.approveForSigning(proposal)
            ledgerNavigator.forwardToSign(sourceEntryId) {
                approval.isCurrent() && ledgerProposalPipeline.transactionProposal.value === proposal
            }
        } catch (e: CancellationException) {
            clearOwnedShield(
                created, { ledgerProposalPipeline.transactionProposal.value }, ledgerProposalPipeline::clear
            )
            throw e
        } catch (e: Exception) {
            clearOwnedShield(
                created, { ledgerProposalPipeline.transactionProposal.value }, ledgerProposalPipeline::clear
            )
            showShieldError(ErrorArgs.ShieldingGeneralError(e), approval, sourceEntryId)
        }
    }
    private fun showShieldError(args: ErrorArgs, approval: ShieldingApproval, outcomeEntryId: String?) {
        if (approval.isCurrent()) {
            if (outcomeEntryId == null) {
                navigateToError(args)
            } else {
                navigationRouter.stageResultFrom(outcomeEntryId, approval::isCurrent) {
                    NavigationCommand.Forward(listOf(navigateToError.stage(args)))
                }
            }
        }
    }
}

private fun clearOwnedShield(
    created: ShieldTransactionProposal?,
    current: () -> TransactionProposal?,
    clear: () -> Unit,
) {
    if (created != null && current() === created) clear()
}
