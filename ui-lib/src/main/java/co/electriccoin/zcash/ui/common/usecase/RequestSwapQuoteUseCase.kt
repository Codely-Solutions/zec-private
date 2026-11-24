package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.FreshShieldedAddressUnavailableException
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_INPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_OUTPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.FLEX_INPUT
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.ZecSwapComposition
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.near.toExactNearQuoteAmountOrNull
import co.electriccoin.zcash.ui.common.model.requireSpendable
import co.electriccoin.zcash.ui.common.model.RecipientAddressPolicy
import co.electriccoin.zcash.ui.common.model.validateSwapRecipient
import co.electriccoin.zcash.ui.common.provider.QuoteRequestDiagnostics
import co.electriccoin.zcash.ui.common.provider.QuoteRequestStage
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.IncomingSwapOwner
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapAssetsUnavailableException
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.swap.mismatch.toMismatchArgs
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteArgs
import co.electriccoin.zcash.ui.screen.texunsupported.TEXUnsupportedArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference

// Keeps the three request modes and their common admission and proposal paths together.
@Suppress("TooManyFunctions")
class RequestSwapQuoteUseCase(
    private val navigationRouter: NavigationRouter,
    private val navigateToErrorUseCase: NavigateToErrorUseCase,
    private val swapRepository: SwapRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val accountDataSource: AccountDataSource,
    private val synchronizerProvider: SynchronizerProvider,
    private val diagnostics: QuoteRequestDiagnostics = QuoteRequestDiagnostics(),
    private val walletWriteAccess: WalletWriteAccess = WalletWriteAccess(),
) {
    private val latestRequest = AtomicReference<RequestOwner?>()

    internal fun captureNavigationOwnership(caller: Job?): () -> Boolean {
        val request = latestRequest.get()
        return { request != null && request.caller === caller && latestRequest.get() === request }
    }

    private suspend fun runRequest(block: suspend () -> Unit) {
        val caller = currentCoroutineContext().job
        coroutineScope {
            val request = RequestOwner(caller, coroutineContext.job)
            latestRequest.getAndSet(request)?.let { previous ->
                previous.child.cancel()
                // Provider work is repository-owned. Retire its quote before the successor's
                // recipient/address checks can suspend and expose the previous result to Continue.
                swapRepository.clearQuote()
            }
            block()
        }
    }

    private class RequestOwner(val caller: Job, val child: Job)

    private data class FundingOwner(val account: WalletAccount, val writeToken: Long)

    suspend fun requestExactInput(
        amount: BigDecimal,
        address: String,
        selectedAsset: SwapAsset,
        slippage: BigDecimal,
        canNavigateToSwapQuote: suspend () -> Boolean,
        contactNetwork: String? = null
    ) = runRequest {
        val recipient = validatedRecipient(
            address, selectedAsset, contactNetwork, canNavigateToSwapQuote, allowPaymentAmount = false
        ) ?: return@runRequest
        val owner = fundingOwner()
        val newAddress = requestFreshAddress(canNavigateToSwapQuote, owner.account) ?: return@runRequest
        if (!walletWriteAccess.permits(owner.writeToken)) return@runRequest
        requestQuote(
            requestQuote = {
                swapRepository.requestExactInputQuote(
                    amount = amount,
                    address = recipient,
                    refundAddress = newAddress,
                    destinationAsset = selectedAsset,
                    slippage = slippage
                )
            },
            fundingOwner = owner,
            canNavigateToSwapQuote = canNavigateToSwapQuote
        )
    }

    suspend fun requestExactOutput(
        amount: BigDecimal,
        address: String,
        selectedAsset: SwapAsset,
        slippage: BigDecimal,
        canNavigateToSwapQuote: suspend () -> Boolean,
        contactNetwork: String? = null
    ) = runRequest {
        val recipient =
            if (amount.toExactNearQuoteAmountOrNull(selectedAsset.decimals) != null) {
                validatedRecipient(address, selectedAsset, contactNetwork, canNavigateToSwapQuote)
            } else {
                if (canNavigateToSwapQuote()) {
                    val error = IllegalArgumentException("Quote amount must be a positive whole number of base units")
                    navigateToErrorUseCase(ErrorArgs.General(error))
                }
                null
            } ?: return@runRequest
        val owner = fundingOwner()
        val newAddress = requestFreshAddress(canNavigateToSwapQuote, owner.account) ?: return@runRequest
        if (!walletWriteAccess.permits(owner.writeToken)) return@runRequest
        requestQuote(
            requestQuote = {
                swapRepository.requestExactOutputQuote(
                    amount = amount,
                    address = recipient,
                    refundAddress = newAddress,
                    destinationAsset = selectedAsset,
                    slippage = slippage
                )
            },
            fundingOwner = owner,
            canNavigateToSwapQuote = canNavigateToSwapQuote
        )
    }

    suspend fun requestFlexInputIntoZec(
        amount: BigDecimal,
        refundAddress: String,
        selectedAsset: SwapAsset,
        slippage: BigDecimal,
        canNavigateToSwapQuote: suspend () -> Boolean,
        contactNetwork: String? = null
    ) = runRequest {
        val refund = validatedRecipient(
            refundAddress, selectedAsset, contactNetwork, canNavigateToSwapQuote, allowPaymentAmount = false
        ) ?: return@runRequest
        val token = walletWriteAccess.token()
        val owner = IncomingSwapOwner(accountDataSource.getSelectedAccount(), token)
        val newAddress = requestFreshAddress(canNavigateToSwapQuote, owner.account) ?: return@runRequest
        if (!walletWriteAccess.permits(token)) return@runRequest
        requestQuote(
            requestQuote = {
                swapRepository
                    .requestFlexInputIntoZec(
                        amount = amount,
                        refundAddress = refund,
                        destinationAddress = newAddress,
                        originAsset = selectedAsset,
                        slippage = slippage,
                        owner = owner,
                    )
            },
            canNavigateToSwapQuote = { canNavigateToSwapQuote() && walletWriteAccess.permits(token) }
        )
    }

    private suspend fun fundingOwner(): FundingOwner {
        val token = walletWriteAccess.token()
        return FundingOwner(accountDataSource.getSelectedAccount(), token)
    }

    private suspend fun requestFreshAddress(canNavigate: suspend () -> Boolean, account: WalletAccount): String? =
        try {
            diagnostics.trace(QuoteRequestStage.FRESH_ADDRESS) {
                accountDataSource.requestNextShieldedAddress(requireFresh = true, account = account)
            }
        } catch (e: FreshShieldedAddressUnavailableException) {
            currentCoroutineContext().ensureActive()
            if (canNavigate()) navigateToErrorUseCase(ErrorArgs.General(e))
            null
        }

    private suspend fun validatedRecipient(
        value: String,
        selectedAsset: SwapAsset,
        contactNetwork: String?,
        canNavigate: suspend () -> Boolean,
        allowPaymentAmount: Boolean = true
    ): String? {
        val validation = validateSwapRecipient(
            value, selectedAsset.chainTicker, selectedAsset.tokenTicker, contactNetwork, allowPaymentAmount,
            tokenContract = selectedAsset.contractAddress,
        )
        val address = validation.input?.address
        val syntax = address?.let { RecipientAddressPolicy.validate(it, selectedAsset.chainTicker.lowercase()) }
        val needsSdk = (syntax as? RecipientAddressPolicy.Validation.Valid)?.requiresZcashSdkValidation == true
        val sdkValid = !needsSdk || withContext(Dispatchers.Default) {
            synchronizerProvider.getSynchronizer().validateAddress(address) !is AddressType.Invalid
        }
        currentCoroutineContext().ensureActive()
        if (validation.isValid && sdkValid) return address
        if (canNavigate()) {
            navigateToErrorUseCase(
                ErrorArgs.General(IllegalArgumentException("Invalid swap recipient for selected asset"))
            )
        }
        return null
    }

    private suspend fun requestQuote(
        requestQuote: suspend () -> Unit,
        canNavigateToSwapQuote: suspend () -> Boolean,
        fundingOwner: FundingOwner? = null,
    ) {
        currentCoroutineContext().ensureActive()
        requestQuote()

        val result = swapRepository.quote.filterNotNull().first { it != SwapQuoteData.Loading }
        currentCoroutineContext().ensureActive()

        val mismatchArgs = (result as? SwapQuoteData.Error)?.toMismatchArgs()
        if (mismatchArgs != null) {
            if (canNavigateToSwapQuote() && swapRepository.consumeReview(result) != null) {
                swapRepository.clearQuote()
                navigationRouter.forward(mismatchArgs)
            }
            return
        }

        if (result is SwapQuoteData.Success && !prepareForReview(result, fundingOwner, canNavigateToSwapQuote)) {
            return
        }

        currentCoroutineContext().ensureActive()
        navigateToPreparedReview(result, fundingOwner, canNavigateToSwapQuote)
    }

    private suspend fun prepareForReview(
        result: SwapQuoteData.Success,
        owner: FundingOwner?,
        canNavigate: suspend () -> Boolean
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (owner != null && !owner.account.isSpendable) {
            onPreparationFailure(result, canNavigate) {
                navigateToErrorUseCase(ErrorArgs.General(AccountOperationUnsupportedException.viewOnly()))
            }
            return false
        }
        return swapRepository.quote.value === result && validateComposition(result, canNavigate) &&
            (owner == null || prepareProposal(result, owner, canNavigate))
    }

    private suspend fun validateComposition(
        result: SwapQuoteData.Success,
        canNavigate: suspend () -> Boolean,
    ): Boolean =
        try {
            ZecSwapComposition.validate(result.quote)
            true
        } catch (e: IllegalArgumentException) {
            if (canNavigate() && swapRepository.quote.value === result) {
                swapRepository.clearQuote()
                navigateToErrorUseCase(ErrorArgs.General(e))
            }
            false
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prepareProposal(
        result: SwapQuoteData.Success,
        owner: FundingOwner,
        canNavigate: suspend () -> Boolean
    ): Boolean =
        try {
            owner.requireCurrent()
            diagnostics.trace(QuoteRequestStage.PROPOSAL) { createProposal(result.quote, owner.account) }
            owner.requireCurrent()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: TexUnsupportedOnKSException) {
            val isLedger = owner.account is LedgerAccount
            onPreparationFailure(result, canNavigate) {
                navigationRouter.forward(TEXUnsupportedArgs(isLedger = isLedger))
            }
            false
        } catch (_: InsufficientFundsException) {
            onPreparationFailure(result, canNavigate) { navigationRouter.forward(InsufficientFundsArgs) }
            false
        } catch (e: Exception) {
            onPreparationFailure(result, canNavigate) { navigateToErrorUseCase(ErrorArgs.General(e)) }
            false
        }

    private suspend fun onPreparationFailure(
        result: SwapQuoteData,
        canNavigate: suspend () -> Boolean,
        navigate: () -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        if (swapRepository.quote.value !== result) return
        swapRepository.clearQuote()
        zashiProposalRepository.clear()
        keystoneProposalRepository.clear()
        ledgerProposalPipeline.clear()
        if (canNavigate()) navigate()
    }

    private suspend fun navigateToPreparedReview(
        result: SwapQuoteData,
        owner: FundingOwner?,
        canNavigateToSwapQuote: suspend () -> Boolean,
    ) {
        if (!canNavigateToSwapQuote() || owner?.isCurrent() == false) return
        if (result is SwapQuoteData.Success && !swapRepository.markReviewReady(result)) {
            if (swapRepository.quote.value === result && !swapRepository.isReviewCurrent(result)) {
                onPreparationFailure(result, canNavigateToSwapQuote) {
                    navigateToErrorUseCase(ErrorArgs.General(SwapAssetsUnavailableException()))
                }
            }
            return
        }
        if (swapRepository.consumeReview(result) != null) {
            navigationRouter.forward(SwapQuoteArgs)
        }
    }

    private suspend fun FundingOwner.requireCurrent() {
        check(isCurrent()) { "The wallet account for this quote changed. Request a new quote." }
    }

    private suspend fun FundingOwner.isCurrent(): Boolean {
        if (!walletWriteAccess.permits(writeToken)) return false
        val current = accountDataSource.getSelectedAccount()
        val sameOwner = current === account || current.sdkAccount.accountUuid == account.sdkAccount.accountUuid
        return sameOwner && walletWriteAccess.permits(writeToken)
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun createProposal(quote: SwapQuote, account: WalletAccount) {
        account.requireSpendable()
        val send =
            ZecSend(
                destination = getWalletAddress(quote.depositAddress.address),
                amount = quote.amountIn.toExactQuoteZatoshi(),
                memo = Memo(""),
                proposal = null
            )

        when (account) {
            is KeystoneAccount -> {
                when (quote.mode) {
                    EXACT_INPUT -> keystoneProposalRepository.createExactInputSwapProposal(send, quote, account)
                    EXACT_OUTPUT -> keystoneProposalRepository.createExactOutputSwapProposal(send, quote, account)
                    FLEX_INPUT -> throw UnsupportedOperationException("Flex input swap not supported")
                }
                keystoneProposalRepository.createPCZTFromProposal(account)
            }

            is LedgerAccount -> {
                when (quote.mode) {
                    EXACT_INPUT -> ledgerProposalPipeline.createExactInputSwapProposal(send, quote, account)
                    EXACT_OUTPUT -> ledgerProposalPipeline.createExactOutputSwapProposal(send, quote, account)
                    FLEX_INPUT -> throw UnsupportedOperationException("Flex input swap not supported")
                }
                ledgerProposalPipeline.createPCZTFromProposal()
            }

            is ViewOnlyAccount -> {
                throw AccountOperationUnsupportedException.viewOnly()
            }

            is ZodlAccount -> {
                when (quote.mode) {
                    EXACT_INPUT -> zashiProposalRepository.createExactInputSwapProposal(send, quote, account)
                    EXACT_OUTPUT -> zashiProposalRepository.createExactOutputSwapProposal(send, quote, account)
                    FLEX_INPUT -> throw UnsupportedOperationException("Flex input swap not supported")
                }
            }
        }
    }

    private suspend fun getWalletAddress(address: String): WalletAddress =
        when (val result = synchronizerProvider.getSynchronizer().validateAddress(address)) {
            AddressType.Shielded -> WalletAddress.Sapling.new(address)
            AddressType.Tex -> WalletAddress.Tex.new(address)
            AddressType.Transparent -> WalletAddress.Transparent.new(address)
            AddressType.Unified -> WalletAddress.Unified.new(address)
            is AddressType.Invalid -> throw IllegalStateException(result.reason)
        }
}

private fun BigDecimal.toExactQuoteZatoshi(): Zatoshi =
    try {
        Zatoshi(longValueExact())
    } catch (e: ArithmeticException) {
        throw InvalidSwapQuoteAmountException(this, e)
    }

private class InvalidSwapQuoteAmountException(
    val amount: BigDecimal,
    cause: ArithmeticException
) : IllegalArgumentException("Swap quote amount must be an exact zatoshi value: $amount", cause)
