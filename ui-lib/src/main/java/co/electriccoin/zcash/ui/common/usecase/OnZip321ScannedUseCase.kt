package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.datasource.UnsupportedZip321PaymentCountException
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.UneditableZip321MemoException
import co.electriccoin.zcash.ui.common.model.zip321MemoTextOrNull
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.ADDRESS_BOOK
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.HOMEPAGE
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.SEND
import co.electriccoin.zcash.ui.screen.send.Send
import co.electriccoin.zcash.ui.screen.texunsupported.TEXUnsupportedArgs
import kotlinx.coroutines.CancellationException

class OnZip321ScannedUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val accountDataSource: AccountDataSource,
    private val navigationRouter: NavigationRouter,
    private val prefillSend: PrefillSendUseCase,
    private val navigateToErrorUseCase: NavigateToErrorUseCase
) {
    /** Returns whether navigation removed the scanner from the back stack. */
    suspend operator fun invoke(zip321: Zip321ParseUriValidation.Valid, scanArgs: ScanArgs): Boolean {
        if (zip321.payment.payments.size != 1) {
            navigateToErrorUseCase(ErrorArgs.General(UnsupportedZip321PaymentCountException()))
            return false
        }
        return when (scanArgs.flow) {
            ADDRESS_BOOK -> {
                addressBookFlow(zip321)
                true
            }

            SEND -> {
                if (scanArgs.isScanZip321Enabled) {
                    sendFlow(zip321)
                } else {
                    sendFlowWithDisabledZip321(zip321)
                }
            }

            HOMEPAGE -> {
                homepageFlow(zip321)
            }
        }
    }

    private fun addressBookFlow(zip321: Zip321ParseUriValidation.Valid) {
        navigationRouter.replace(
            AddZashiABContactArgs(
                zip321.payment.payments[0]
                    .recipientAddress.value
            )
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun homepageFlow(zip321: Zip321ParseUriValidation.Valid): Boolean =
        try {
            val proposal =
                when (accountDataSource.getSelectedAccount()) {
                    is KeystoneAccount -> {
                        val result = keystoneProposalRepository.createZip321Proposal(zip321.zip321Uri)
                        keystoneProposalRepository.createPCZTFromProposal()
                        result
                    }

                    is LedgerAccount -> {
                        val result = ledgerProposalPipeline.createZip321Proposal(zip321.zip321Uri)
                        ledgerProposalPipeline.createPCZTFromProposal()
                        result
                    }

                    is ViewOnlyAccount -> {
                        throw AccountOperationUnsupportedException.viewOnly()
                    }

                    is ZodlAccount -> {
                        zashiProposalRepository.createZip321Proposal(zip321.zip321Uri)
                    }
                }

            if (proposal.binaryMemoByteCount == null) prefillSend.request(
                PrefillSendData.All(
                    amount = proposal.amount,
                    address = proposal.destination.address,
                    fee = null,
                    memos =
                        proposal.memo.value
                            .takeIf { it.isNotEmpty() }
                            ?.let { listOf(it) }
                )
            )
            if (proposal.binaryMemoByteCount == null) {
                navigationRouter.replace(Send(), ReviewTransactionArgs)
            } else {
                navigationRouter.replace(ReviewTransactionArgs)
            }
            true
        } catch (_: TexUnsupportedOnKSException) {
            val isLedger = accountDataSource.getSelectedAccount() is LedgerAccount
            navigationRouter.forward(TEXUnsupportedArgs(isLedger = isLedger))
            keystoneProposalRepository.clear()
            ledgerProposalPipeline.clear()
            zashiProposalRepository.clear()
            false
        } catch (_: InsufficientFundsException) {
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
            ledgerProposalPipeline.clear()
            navigationRouter.replace(InsufficientFundsArgs)
            true
        } catch (_: TransactionProposalNotCreatedException) {
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
            ledgerProposalPipeline.clear()
            canEdit(zip321).also { editable ->
                if (editable) {
                    prefillSend.requestFromZip321(zip321.payment)
                    navigationRouter.replace(Send())
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            navigateToErrorUseCase(ErrorArgs.General(e))
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
            ledgerProposalPipeline.clear()
            false
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendFlow(zip321: Zip321ParseUriValidation.Valid): Boolean =
        try {
            val proposal =
                when (accountDataSource.getSelectedAccount()) {
                    is KeystoneAccount -> {
                        val result = keystoneProposalRepository.createZip321Proposal(zip321.zip321Uri)
                        keystoneProposalRepository.createPCZTFromProposal()
                        result
                    }

                    is LedgerAccount -> {
                        val result = ledgerProposalPipeline.createZip321Proposal(zip321.zip321Uri)
                        ledgerProposalPipeline.createPCZTFromProposal()
                        result
                    }

                    is ViewOnlyAccount -> {
                        throw AccountOperationUnsupportedException.viewOnly()
                    }

                    is ZodlAccount -> {
                        zashiProposalRepository.createZip321Proposal(zip321.zip321Uri)
                    }
                }

            if (proposal.binaryMemoByteCount == null) prefillSend.request(
                PrefillSendData.All(
                    amount = proposal.amount,
                    address = proposal.destination.address,
                    fee = null,
                    memos =
                        proposal.memo.value
                            .takeIf { it.isNotEmpty() }
                            ?.let { listOf(it) }
                )
            )
            navigationRouter.replace(ReviewTransactionArgs)
            true
        } catch (_: TexUnsupportedOnKSException) {
            val isLedger = accountDataSource.getSelectedAccount() is LedgerAccount
            navigationRouter.forward(TEXUnsupportedArgs(isLedger = isLedger))
            keystoneProposalRepository.clear()
            ledgerProposalPipeline.clear()
            zashiProposalRepository.clear()
            false
        } catch (_: InsufficientFundsException) {
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
            ledgerProposalPipeline.clear()
            navigationRouter.replace(InsufficientFundsArgs)
            true
        } catch (_: TransactionProposalNotCreatedException) {
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
            ledgerProposalPipeline.clear()
            canEdit(zip321).also { editable ->
                if (editable) {
                    prefillSend.requestFromZip321(zip321.payment)
                    navigationRouter.back()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
            ledgerProposalPipeline.clear()
            navigateToErrorUseCase(ErrorArgs.General(e)) { replace(it) }
            true
        }

    private fun sendFlowWithDisabledZip321(zip321: Zip321ParseUriValidation.Valid): Boolean {
        if (!canEdit(zip321)) return false
        prefillSend.request(
            PrefillSendData.FromAddressScan(
                address =
                    zip321.payment.payments[0]
                        .recipientAddress.value
            )
        )
        navigationRouter.back()
        return true
    }

    private fun canEdit(zip321: Zip321ParseUriValidation.Valid): Boolean {
        if (zip321.payment.payments.single().memo?.data?.zip321MemoTextOrNull() != null ||
            zip321.payment.payments.single().memo == null
        ) return true
        navigateToErrorUseCase(ErrorArgs.General(UneditableZip321MemoException()))
        return false
    }
}
