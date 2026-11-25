package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.extension.normalizedSendAmount
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import co.electriccoin.zcash.ui.screen.texunsupported.TEXUnsupportedArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class CreateProposalUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val accountDataSource: AccountDataSource,
    private val navigationRouter: NavigationRouter,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(zecSend: ZecSend, floor: Boolean, sendEntryId: String, canReview: () -> Boolean) {
        try {
            val normalized by lazy { zecSend.normalize(floor) }
            val account = accountDataSource.getSelectedAccount()
            if (!canReview()) return
            when (account) {
                is KeystoneAccount -> {
                    keystoneProposalRepository.createProposal(normalized)
                    keystoneProposalRepository.createPCZTFromProposal()
                }

                is LedgerAccount -> {
                    ledgerProposalPipeline.createProposal(normalized)
                    ledgerProposalPipeline.createPCZTFromProposal()
                }

                is ViewOnlyAccount -> {
                    throw AccountOperationUnsupportedException.viewOnly()
                }

                is ZodlAccount -> {
                    zashiProposalRepository.createProposal(normalized)
                }
            }
            forwardFromSend(sendEntryId, ReviewTransactionArgs, canReview)
        } catch (e: CancellationException) {
            throw e
        } catch (_: TexUnsupportedOnKSException) {
            val isLedger = accountDataSource.getSelectedAccount() is LedgerAccount
            forwardFromSend(sendEntryId, TEXUnsupportedArgs(isLedger = isLedger), canReview)
            clearIfCurrent(canReview)
        } catch (_: InsufficientFundsException) {
            clearIfCurrent(canReview)
            forwardFromSend(sendEntryId, InsufficientFundsArgs, canReview)
        } catch (e: Exception) {
            clearIfCurrent(canReview)
            throw e
        }
    }

    private fun forwardFromSend(sendEntryId: String, route: Any, canReview: () -> Boolean) {
        if (!canReview()) return
        navigationRouter.custom { current ->
            if (current?.id == sendEntryId && canReview()) {
                NavigationCommand.Forward(listOf(route), sendEntryId, canReview)
            } else {
                null
            }
        }
    }

    private suspend fun clearIfCurrent(canReview: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!canReview()) return
        keystoneProposalRepository.clear()
        ledgerProposalPipeline.clear()
        zashiProposalRepository.clear()
    }

    private fun ZecSend.normalize(floor: Boolean): ZecSend {
        val amount = requireNotNull(amount.normalizedSendAmount(floor)) { "Invalid send amount" }
        val supportsMemo = destination is WalletAddress.Unified || destination is WalletAddress.Sapling
        require(amount.value > 0L || (supportsMemo && memo.value.isNotEmpty())) {
            "Zero-ZEC sends require a shielded recipient and a memo"
        }
        return copy(amount = amount, memo = if (supportsMemo) memo else Memo(""))
    }
}
