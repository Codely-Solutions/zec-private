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
import co.electriccoin.zcash.wallet.foundation.FundingCapability
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.near.toExactNearQuoteAmountOrNull
import co.electriccoin.zcash.ui.common.model.requireSpendable
import co.electriccoin.zcash.ui.common.model.RecipientAddressPolicy
import co.electriccoin.zcash.ui.common.model.validateSwapRecipient
import co.electriccoin.zcash.ui.common.provider.QuoteRequestDiagnostics
import co.electriccoin.zcash.ui.common.provider.QuoteRequestStage
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
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

    suspend fun requestExactInput(
        amount: BigDecimal,
        address: String,
        selectedAsset: SwapAsset,
        slippage: BigDecimal,
        canNavigateToSwapQuote: () -> Boolean,
        contactNetwork: String? = null
    ) = runRequest {
        val recipient = validatedRecipient(
            address, selectedAsset, contactNetwork, canNavigateToSwapQuote, allowPaymentAmount = false
        ) ?: return@runRequest
        val newAddress = requestFreshAddress(canNavigateToSwapQuote) ?: return@runRequest
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
            funding = ZecSwapComposition.funding(EXACT_INPUT),
            canNavigateToSwapQuote = canNavigateToSwapQuote
        )
    }

    suspend fun requestExactOutput(
        amount: BigDecimal,
        address: String,
        selectedAsset: SwapAsset,
        slippage: BigDecimal,
        canNavigateToSwapQuote: () -> Boolean,
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
        val newAddress = requestFreshAddress(canNavigateToSwapQuote) ?: return@runRequest
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
            funding = ZecSwapComposition.funding(EXACT_OUTPUT),
            canNavigateToSwapQuote = canNavigateToSwapQuote
        )
    }

    suspend fun requestFlexInputIntoZec(
        amount: BigDecimal,
        refundAddress: String,
        selectedAsset: SwapAsset,
        slippage: BigDecimal,
        canNavigateToSwapQuote: () -> Boolean,
        contactNetwork: String? = null
    ) = runRequest {
        val refund = validatedRecipient(
            refundAddress, selectedAsset, contactNetwork, canNavigateToSwapQuote, allowPaymentAmount = false
        ) ?: return@runRequest
        val newAddress = requestFreshAddress(canNavigateToSwapQuote) ?: return@runRequest
        requestQuote(
            requestQuote = {
                swapRepository
                    .requestFlexInputIntoZec(
                        amount = amount,
                        refundAddress = refund,
                        destinationAddress = newAddress,
                        originAsset = selectedAsset,
                        slippage = slippage
                    )
            },
            funding = ZecSwapComposition.funding(FLEX_INPUT),
            canNavigateToSwapQuote = canNavigateToSwapQuote
        )
    }

    private suspend fun requestFreshAddress(canNavigate: () -> Boolean): String? =
        try {
            diagnostics.trace(QuoteRequestStage.FRESH_ADDRESS) {
                accountDataSource.requestNextShieldedAddress(requireFresh = true)
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
        canNavigate: () -> Boolean,
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

    private suspend fun rejectUnsupportedFunding(
        funding: FundingCapability,
        result: SwapQuoteData,
        canNavigate: () -> Boolean
    ): Boolean {
        if (funding != FundingCapability.LOCAL_TRANSACTION || accountDataSource.getSelectedAccount().isSpendable) {
            return false
        }
        onPreparationFailure(result, canNavigate) {
            navigateToErrorUseCase(ErrorArgs.General(AccountOperationUnsupportedException.viewOnly()))
        }
        return true
    }

    private suspend fun requestQuote(
        requestQuote: suspend () -> Unit,
        funding: FundingCapability,
        canNavigateToSwapQuote: () -> Boolean
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

        if (result is SwapQuoteData.Success && !prepareForReview(result, funding, canNavigateToSwapQuote)) {
            return
        }

        currentCoroutineContext().ensureActive()
        navigateToPreparedReview(result, canNavigateToSwapQuote)
    }

    private suspend fun prepareForReview(
        result: SwapQuoteData.Success,
        funding: FundingCapability,
        canNavigate: () -> Boolean
    ): Boolean {
        if (rejectUnsupportedFunding(funding, result, canNavigate)) return false
        currentCoroutineContext().ensureActive()
        return swapRepository.quote.value === result && validateComposition(result, canNavigate) &&
            (funding != FundingCapability.LOCAL_TRANSACTION || prepareProposal(result, canNavigate))
    }

    private fun validateComposition(result: SwapQuoteData.Success, canNavigate: () -> Boolean): Boolean =
        try {
            ZecSwapComposition.validate(result.quote)
            true
        } catch (e: IllegalArgumentException) {
            if (canNavigate()) {
                swapRepository.clearQuote()
                navigateToErrorUseCase(ErrorArgs.General(e))
            }
            false
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prepareProposal(
        result: SwapQuoteData.Success,
        canNavigate: () -> Boolean
    ): Boolean =
        try {
            diagnostics.trace(QuoteRequestStage.PROPOSAL) { createProposal(result.quote) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: TexUnsupportedOnKSException) {
            val isLedger = accountDataSource.getSelectedAccount() is LedgerAccount
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
        canNavigate: () -> Boolean,
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

    private fun navigateToPreparedReview(result: SwapQuoteData, canNavigateToSwapQuote: () -> Boolean) {
        if (result is SwapQuoteData.Success && !swapRepository.markReviewReady(result)) return
        if (canNavigateToSwapQuote() && swapRepository.consumeReview(result) != null) {
            navigationRouter.forward(SwapQuoteArgs)
        }
    }

    /**
     * Spends from the selected account, so an account that cannot spend is refused before anything
     * is derived from the quote.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun createProposal(quote: SwapQuote) {
        val account = accountDataSource.getSelectedAccount().requireSpendable()
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
                    EXACT_INPUT -> keystoneProposalRepository.createExactInputSwapProposal(send, quote)
                    EXACT_OUTPUT -> keystoneProposalRepository.createExactOutputSwapProposal(send, quote)
                    FLEX_INPUT -> throw UnsupportedOperationException("Flex input swap not supported")
                }
                keystoneProposalRepository.createPCZTFromProposal()
            }

            is LedgerAccount -> {
                when (quote.mode) {
                    EXACT_INPUT -> ledgerProposalPipeline.createExactInputSwapProposal(send, quote)
                    EXACT_OUTPUT -> ledgerProposalPipeline.createExactOutputSwapProposal(send, quote)
                    FLEX_INPUT -> throw UnsupportedOperationException("Flex input swap not supported")
                }
                ledgerProposalPipeline.createPCZTFromProposal()
            }

            is ViewOnlyAccount -> {
                throw AccountOperationUnsupportedException.viewOnly()
            }

            is ZodlAccount -> {
                when (quote.mode) {
                    EXACT_INPUT -> zashiProposalRepository.createExactInputSwapProposal(send, quote)
                    EXACT_OUTPUT -> zashiProposalRepository.createExactOutputSwapProposal(send, quote)
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
