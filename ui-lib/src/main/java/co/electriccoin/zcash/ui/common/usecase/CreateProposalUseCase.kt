package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.NavigationRouter
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

class CreateProposalUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val accountDataSource: AccountDataSource,
    private val navigationRouter: NavigationRouter,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(zecSend: ZecSend, floor: Boolean) {
        try {
            val normalized by lazy { zecSend.normalize(floor) }
            when (accountDataSource.getSelectedAccount()) {
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
            navigationRouter.forward(ReviewTransactionArgs)
        } catch (_: TexUnsupportedOnKSException) {
            val isLedger = accountDataSource.getSelectedAccount() is LedgerAccount
            navigationRouter.forward(TEXUnsupportedArgs(isLedger = isLedger))
            keystoneProposalRepository.clear()
            ledgerProposalPipeline.clear()
            zashiProposalRepository.clear()
        } catch (_: InsufficientFundsException) {
            keystoneProposalRepository.clear()
            ledgerProposalPipeline.clear()
            zashiProposalRepository.clear()
            navigationRouter.forward(InsufficientFundsArgs)
        } catch (e: Exception) {
            keystoneProposalRepository.clear()
            ledgerProposalPipeline.clear()
            zashiProposalRepository.clear()
            throw e
        }
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
