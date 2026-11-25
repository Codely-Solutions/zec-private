package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.whileAtEntry
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class OnZip321ScannedUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val accountDataSource: AccountDataSource,
    private val navigationRouter: NavigationRouter,
    private val prefillSend: PrefillSendUseCase,
    private val navigateToErrorUseCase: NavigateToErrorUseCase
) {
    /** Returns whether acknowledged navigation moved away from the scanner. */
    suspend operator fun invoke(
        zip321: Zip321ParseUriValidation.Valid,
        scanArgs: ScanArgs,
        sourceEntryId: String? = null,
    ): Boolean =
        when {
            !navigationRouter.isAtEntry(sourceEntryId) -> false
            zip321.payment.payments.size != 1 -> {
                showError(sourceEntryId, ErrorArgs.General(UnsupportedZip321PaymentCountException()))
                false
            }
            else -> when (scanArgs.flow) {
                ADDRESS_BOOK -> navigationRouter.commitFrom(sourceEntryId) {
                    val address = zip321.payment.payments.single().recipientAddress.value
                    NavigationCommand.Replace(listOf(AddZashiABContactArgs(address)))
                }
                SEND -> if (scanArgs.isScanZip321Enabled) {
                    prepareFlow(zip321, sourceEntryId, fromHome = false)
                } else {
                    sendAddressOnly(zip321, sourceEntryId)
                }
                HOMEPAGE -> prepareFlow(zip321, sourceEntryId, fromHome = true)
            }
        }

    private suspend fun prepareProposal(uri: String) =
        when (accountDataSource.getSelectedAccount()) {
            is KeystoneAccount -> keystoneProposalRepository.createZip321Proposal(uri).also {
                keystoneProposalRepository.createPCZTFromProposal()
            }
            is LedgerAccount -> ledgerProposalPipeline.createZip321Proposal(uri).also {
                ledgerProposalPipeline.createPCZTFromProposal()
            }
            is ViewOnlyAccount -> throw AccountOperationUnsupportedException.viewOnly()
            is ZodlAccount -> zashiProposalRepository.createZip321Proposal(uri)
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prepareFlow(
        zip321: Zip321ParseUriValidation.Valid,
        sourceEntryId: String?,
        fromHome: Boolean,
    ): Boolean =
        try {
            val proposal = navigationRouter.whileAtEntry(sourceEntryId) { prepareProposal(zip321.zip321Uri) }
            proposal != null && navigationRouter.commitFrom(sourceEntryId) {
                val editable = proposal.binaryMemoByteCount == null
                if (editable) {
                    prefillSend.request(
                        PrefillSendData.All(
                            amount = proposal.amount,
                            address = proposal.destination.address,
                            fee = null,
                            memos = proposal.memo.value.takeIf { it.isNotEmpty() }?.let { listOf(it) },
                        )
                    )
                }
                val routes = if (fromHome && editable) {
                    listOf(Send(), ReviewTransactionArgs)
                } else {
                    listOf(ReviewTransactionArgs)
                }
                NavigationCommand.Replace(routes)
            }
        } catch (_: TexUnsupportedOnKSException) {
            val isLedger = accountDataSource.getSelectedAccount() is LedgerAccount
            clearIfCurrent(sourceEntryId)
            navigationRouter.commitFrom(sourceEntryId) {
                NavigationCommand.Forward(listOf(TEXUnsupportedArgs(isLedger = isLedger)))
            }
            false
        } catch (_: InsufficientFundsException) {
            clearIfCurrent(sourceEntryId)
            navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.Replace(listOf(InsufficientFundsArgs)) }
        } catch (_: TransactionProposalNotCreatedException) {
            clearIfCurrent(sourceEntryId)
            editUnpreparedPayment(zip321, sourceEntryId, fromHome)
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            clearIfCurrent(sourceEntryId)
            showError(sourceEntryId, ErrorArgs.General(e), replaceScanner = !fromHome) && !fromHome
        }

    private suspend fun editUnpreparedPayment(
        zip321: Zip321ParseUriValidation.Valid,
        sourceEntryId: String?,
        fromHome: Boolean,
    ): Boolean {
        if (!canEdit(zip321, sourceEntryId)) return false
        return navigationRouter.commitFrom(sourceEntryId) {
            prefillSend.requestFromZip321(zip321.payment)
            if (fromHome) NavigationCommand.Replace(listOf(Send())) else NavigationCommand.Back
        }
    }

    private suspend fun sendAddressOnly(zip321: Zip321ParseUriValidation.Valid, sourceEntryId: String?): Boolean {
        if (!canEdit(zip321, sourceEntryId)) return false
        return navigationRouter.commitFrom(sourceEntryId) {
            val address = zip321.payment.payments.single().recipientAddress.value
            prefillSend.request(PrefillSendData.FromAddressScan(address))
            NavigationCommand.Back
        }
    }

    private suspend fun canEdit(zip321: Zip321ParseUriValidation.Valid, sourceEntryId: String?): Boolean {
        val memo = zip321.payment.payments.single().memo
        if (memo == null || memo.data.zip321MemoTextOrNull() != null) return true
        showError(sourceEntryId, ErrorArgs.General(UneditableZip321MemoException()))
        return false
    }

    private suspend fun clearIfCurrent(sourceEntryId: String?) {
        currentCoroutineContext().ensureActive()
        if (!navigationRouter.isAtEntry(sourceEntryId)) return
        zashiProposalRepository.clear()
        keystoneProposalRepository.clear()
        ledgerProposalPipeline.clear()
    }

    private suspend fun showError(
        sourceEntryId: String?,
        args: ErrorArgs,
        replaceScanner: Boolean = false,
    ): Boolean = if (sourceEntryId == null) {
        if (replaceScanner) navigateToErrorUseCase(args) { replace(it) } else navigateToErrorUseCase(args)
        true
    } else {
        navigationRouter.commitFrom(sourceEntryId) {
            val routes = listOf(navigateToErrorUseCase.stage(args))
            if (replaceScanner) NavigationCommand.Replace(routes) else NavigationCommand.Forward(routes)
        }
    }
}
