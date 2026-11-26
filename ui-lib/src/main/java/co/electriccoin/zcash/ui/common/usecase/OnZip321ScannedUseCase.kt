package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.common.datasource.PaymentDraft
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.datasource.UnsupportedZip321PaymentCountException
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.UneditableZip321MemoException
import co.electriccoin.zcash.ui.common.model.zip321MemoTextOrNull
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.ADDRESS_BOOK
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.HOMEPAGE
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.SEND
import co.electriccoin.zcash.ui.screen.send.Send
import co.electriccoin.zcash.ui.screen.texunsupported.TEXUnsupportedArgs
import kotlinx.coroutines.CancellationException

class OnZip321ScannedUseCase(
    private val review: PreparePaymentReviewUseCase,
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
                    prepareFlow(zip321, scanArgs, sourceEntryId)
                } else {
                    sendAddressOnly(zip321, scanArgs.sendEntryId, sourceEntryId)
                }
                HOMEPAGE -> prepareFlow(zip321, scanArgs, sourceEntryId)
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prepareFlow(
        zip321: Zip321ParseUriValidation.Valid,
        scanArgs: ScanArgs,
        sourceEntryId: String?,
    ): Boolean {
        val fromHome = scanArgs.flow == HOMEPAGE
        val owner = review.capture(sourceEntryId) ?: return false
        val walletPermit = owner.permitsWallet
        return try {
            review.open(owner, PaymentDraft.Zip321(zip321.zip321Uri)) { route, prepared ->
                val proposal = prepared as Zip321TransactionProposal
                val send = if (proposal.binaryMemoByteCount == null) {
                    stageEditableDraft(
                        PrefillSendData.All(
                            amount = proposal.amount,
                            address = proposal.destination.address,
                            fee = null,
                            memos = proposal.memo.value.takeIf { it.isNotEmpty() }?.let { listOf(it) },
                        ),
                        scanArgs,
                        walletPermit,
                    )
                } else {
                    null
                }
                NavigationCommand.Replace(listOfNotNull(send, route))
            }
        } catch (_: TexUnsupportedOnKSException) {
            navigationRouter.commitFrom(sourceEntryId, owner.permits) {
                NavigationCommand.Forward(listOf(TEXUnsupportedArgs(isLedger = owner.account is LedgerAccount)))
            }
            false
        } catch (_: InsufficientFundsException) {
            navigationRouter.commitFrom(sourceEntryId, owner.permits) {
                NavigationCommand.Replace(listOf(InsufficientFundsArgs))
            }
        } catch (_: TransactionProposalNotCreatedException) {
            editUnpreparedPayment(zip321, scanArgs, sourceEntryId, owner.permits, walletPermit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            showError(
                sourceEntryId, ErrorArgs.General(error), replaceScanner = !fromHome, isStillAllowed = owner.permits,
            ) && !fromHome
        }
    }

    private suspend fun editUnpreparedPayment(
        zip321: Zip321ParseUriValidation.Valid,
        scanArgs: ScanArgs,
        sourceEntryId: String?,
        isStillAllowed: () -> Boolean,
        walletPermit: () -> Boolean,
    ): Boolean {
        if (!isStillAllowed() || !canEdit(zip321, sourceEntryId, isStillAllowed)) return false
        return navigationRouter.commitFrom(sourceEntryId, { isStillAllowed() && walletPermit() }) {
            val send = stageEditableDraft(zip321.payment.toEditableSendDraft(), scanArgs, walletPermit)
            if (send == null) NavigationCommand.Back else NavigationCommand.Replace(listOf(send))
        }
    }

    private fun stageEditableDraft(
        draft: PrefillSendData.All,
        scanArgs: ScanArgs,
        walletPermit: () -> Boolean,
    ): Send? {
        if (scanArgs.flow == HOMEPAGE) return Send(prefillToken = prefillSend.forNewSend(draft, walletPermit))
        scanArgs.sendEntryId?.let { prefillSend.forExistingSend(it, draft, walletPermit) }
        return null
    }

    private suspend fun sendAddressOnly(
        zip321: Zip321ParseUriValidation.Valid,
        sendEntryId: String?,
        sourceEntryId: String?,
    ): Boolean {
        val walletPermit = review.captureWalletOwner()
        if (!canEdit(zip321, sourceEntryId) || sendEntryId == null || walletPermit == null) return false
        return navigationRouter.commitFrom(sourceEntryId, walletPermit) {
            val address = zip321.payment.payments.single().recipientAddress.value
            prefillSend.forExistingSend(sendEntryId, PrefillSendData.FromAddressScan(address), walletPermit)
            NavigationCommand.Back
        }
    }

    private suspend fun canEdit(
        zip321: Zip321ParseUriValidation.Valid,
        sourceEntryId: String?,
        isStillAllowed: () -> Boolean = { true },
    ): Boolean {
        val memo = zip321.payment.payments.single().memo
        if (memo == null || memo.data.zip321MemoTextOrNull() != null) return true
        showError(sourceEntryId, ErrorArgs.General(UneditableZip321MemoException()), isStillAllowed = isStillAllowed)
        return false
    }

    private suspend fun showError(
        sourceEntryId: String?,
        args: ErrorArgs,
        replaceScanner: Boolean = false,
        isStillAllowed: () -> Boolean = { true },
    ): Boolean = navigationRouter.commitFrom(sourceEntryId, isStillAllowed) {
        val routes = listOf(navigateToErrorUseCase.stage(args))
        if (replaceScanner) NavigationCommand.Replace(routes) else NavigationCommand.Forward(routes)
    }
}
