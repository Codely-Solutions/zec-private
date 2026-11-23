package co.electriccoin.zcash.ui.screen.pay

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.FakeSwapQuote
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.near.NearSwapAsset
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.GetCuratedSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPreselectedSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.IsABContactHintVisibleUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapQuoteIfAvailableUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapRefundWarningUseCase
import co.electriccoin.zcash.ui.common.usecase.RequestSwapQuoteUseCase
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.screen.error.ErrorDialog
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.swap.SwapVM
import co.electriccoin.zcash.ui.screen.swap.SwapVMMapper
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteArgs
import co.electriccoin.zcash.ui.screen.texunsupported.TEXUnsupportedArgs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SwapRequestCancellationTest {
    @Test
    fun confirmingCrossPayCancelSuppressesPreparationSuccessAndEveryFailure() {
        for (failure in listOf(null, TexUnsupportedOnKSException(), InsufficientFundsException(),
            IllegalStateException("synthetic preparation error"), CancellationException())) {
            exercise(SwapMode.EXACT_OUTPUT, failure)
        }
    }

    @Test
    fun confirmingIncomingCancelSuppressesProviderSuccessAndFailure() {
        exercise(SwapMode.FLEX_INPUT)
        exercise(SwapMode.FLEX_INPUT, IllegalStateException("synthetic provider error"))
        exercise(SwapMode.FLEX_INPUT, CancellationException())
    }

    @Test
    fun continuingEachDirectionOpensExactlyOneReview() {
        exercise(SwapMode.EXACT_OUTPUT, shouldContinue = true)
        exercise(SwapMode.FLEX_INPUT, shouldContinue = true)
    }

    @Test
    fun lateFailureCannotClearNewerQuoteOrProposal() =
        exercise(SwapMode.EXACT_OUTPUT, InsufficientFundsException(), replace = true)

    @Test
    fun dismissalCannotPopReplacementWhileItPreparesARefundAddress() =
        exercise(SwapMode.EXACT_OUTPUT, replaceBeforeRoot = true)

    @Test
    fun refreshedCrossPayKeepsLastEditedUnitAndRejectsThePreviousQuoteButton() =
        exercise(SwapMode.EXACT_OUTPUT, checkPriceRefresh = true)

    // Exercises both production screens with the same ports, virtual clock and replacement ordering.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun exercise(
        mode: SwapMode,
        failure: Exception? = null,
        shouldContinue: Boolean = false,
        replace: Boolean = false,
        replaceBeforeRoot: Boolean = false,
        checkPriceRefresh: Boolean = false,
    ) = runTest(timeout = 15.seconds) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
        try {
            val btc = SwapAssetTestFixture.asset()
            val zec = SwapAssetTestFixture.zecAsset()
            val assets = SwapAssetTestFixture.assetsData(data = listOf(btc), zecAsset = zec)
            val recipient = "1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu"
            val refund = "synthetic-refund"
            val amount = BigDecimal("0.0001")
            val incoming = mode == SwapMode.FLEX_INPUT
            fun quote(deposit: String) = FakeSwapQuote(
                originAsset = if (incoming) btc else zec,
                destinationAsset = if (incoming) zec else btc,
                mode = mode,
                amountIn = BigDecimal("100000000"),
                amountInFormatted = if (incoming) amount else BigDecimal.ONE,
                amountOutFormatted = if (incoming) BigDecimal.ONE else amount,
                depositAddress = deposit,
                destinationAddress = if (incoming) refund else recipient,
                refundAddress = if (incoming) recipient else refund,
            )
            val firstQuote = quote("synthetic-deposit")
            val nextQuote = quote("synthetic-replacement")
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            suspend fun finishFirst() = withContext(NonCancellable) {
                started.complete(Unit)
                finish.await()
                if (failure != null) throw failure
            }

            val source = mockk<SwapDataSource> {
                var next = false
                coEvery { requestQuote(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                    val result = if (next) nextQuote else firstQuote
                    next = true
                    if (incoming) finishFirst()
                    result
                }
            }
            val repository = SwapRepositoryImpl(source, mockk()).also {
                it.assets.value = assets
                it.scope = backgroundScope
            }
            val router = mockk<NavigationRouter>(relaxed = true)
            val account = mockk<ZodlAccount> {
                every { isSpendable } returns true
                every { canSpend(any()) } returns true
            }
            val replacementAddress = CompletableDeferred<Unit>()
            val accountSource = mockk<AccountDataSource> {
                var freshAddressRequests = 0
                coEvery { requestNextShieldedAddress(requireFresh = true) } coAnswers {
                    if (freshAddressRequests++ > 0) replacementAddress.await()
                    refund
                }
                coEvery { getSelectedAccount() } returns account
            }
            val synchronizer = mockk<Synchronizer> {
                coEvery { validateAddress(any()) } returns AddressType.Unified
            }
            val proposals = mockk<ZashiProposalRepository>(relaxed = true)
            coEvery { proposals.createExactOutputSwapProposal(any(), firstQuote) } coAnswers {
                finishFirst()
                mockk(relaxed = true)
            }
            coEvery { proposals.createExactOutputSwapProposal(any(), nextQuote) } returns mockk(relaxed = true)
            val request = RequestSwapQuoteUseCase(
                navigationRouter = router,
                navigateToErrorUseCase = NavigateToErrorUseCase(router),
                swapRepository = repository,
                zashiProposalRepository = proposals,
                keystoneProposalRepository = mockk(relaxed = true),
                ledgerProposalPipeline = mockk(relaxed = true),
                accountDataSource = accountSource,
                synchronizerProvider = mockk<SynchronizerProvider> {
                    coEvery { getSynchronizer() } returns synchronizer
                },
            )
            val curated = mockk<GetCuratedSwapAssetsUseCase> {
                every { observe() } returns flowOf(assets)
            }
            val selectedAccount = mockk<GetSelectedWalletAccountUseCase> {
                every { observe() } returns flowOf(account)
            }
            val preselected = mockk<GetPreselectedSwapAssetUseCase> {
                coEvery { this@mockk.invoke() } returns btc
            }
            val warning = mockk<NavigateToSwapRefundWarningUseCase> {
                coEvery { this@mockk.invoke(any(), any()) } returns true
            }
            val cancel = CancelSwapUseCase(repository, router)
            val continueQuote = NavigateToSwapQuoteIfAvailableUseCase(repository, router)
            val pay = if (incoming) null else PayVM(
                getCuratedSwapAssetsUseCase = curated,
                getSelectedWalletAccount = selectedAccount,
                swapRepository = repository,
                cancelSwap = cancel,
                navigationRouter = router,
                requestSwapQuote = request,
                navigateToSwapQuoteIfAvailable = continueQuote,
                exactOutputVMMapper = ExactOutputVMMapper(),
                navigateToScanAddress = mockk(),
                navigateToSelectSwapRecipient = mockk(),
                isABContactHintVisible = mockk<IsABContactHintVisibleUseCase> {
                    every { observe(any(), any()) } returns flowOf(false)
                },
                getPreselectedSwapAsset = preselected,
                navigateToSlippage = mockk(),
                navigateToSwapAssetPicker = mockk(),
                navigateToSwapRefundWarning = warning,
            )
            val swap = if (!incoming) null else SwapVM(
                getCuratedSwapAssetsUseCase = curated,
                getSelectedWalletAccount = selectedAccount,
                getPreselectedSwapAsset = preselected,
                swapRepository = repository,
                navigateToSwapInfo = mockk(),
                cancelSwap = cancel,
                navigationRouter = router,
                requestSwapQuote = request,
                navigateToSwapQuoteIfAvailable = continueQuote,
                swapVMMapper = SwapVMMapper(),
                navigateToScanAddress = mockk(),
                navigateToSelectSwapRecipient = mockk(),
                navigateToSlippage = mockk(),
                navigateToSwapAssetPicker = mockk(),
                navigateToSwapRefundWarning = warning,
            )
            val cancelState = pay?.cancelState ?: checkNotNull(swap).cancelState
            try {
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cancelState.collect {} }
                if (pay != null) {
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pay.state.collect {} }
                    runCurrent()
                    fun form() = assertNotNull(pay.state.value)
                    form().address.onValueChange(recipient)
                    runCurrent()
                    if (checkPriceRefresh) {
                        checkPriceRefresh(pay, repository, assertIs<NearSwapAsset>(btc))
                        return@runTest
                    }
                    form().amount.onValueChange(NumberTextFieldInnerState.fromAmount(amount))
                    runCurrent()
                    val button = assertNotNull(form().primaryButton)
                    assertTrue(button.isEnabled)
                    assertNotNull(button.onClick).invoke()
                    runCurrent()
                    assertTrue(started.isCompleted)
                    form().onBack()
                } else {
                    val vm = checkNotNull(swap)
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                    runCurrent()
                    fun form() = assertNotNull(vm.state.value)
                    form().address.onValueChange(recipient)
                    runCurrent()
                    form().amountTextField.textField.onValueChange(NumberTextFieldInnerState.fromAmount(amount))
                    runCurrent()
                    val button = assertNotNull(form().primaryButton)
                    assertTrue(button.isEnabled)
                    assertNotNull(button.onClick).invoke()
                    runCurrent()
                    assertTrue(started.isCompleted)
                    form().onBack()
                }
                runCurrent()
                val sheet = assertNotNull(cancelState.value)
                when {
                    replace -> {
                        backgroundScope.launch {
                            request.requestExactOutput(amount, recipient, btc, BigDecimal("2"), { true })
                        }
                        runCurrent()
                        assertNull(repository.quote.value)
                        replacementAddress.complete(Unit)
                        runCurrent()
                        assertSame(nextQuote, (repository.quote.value as SwapQuoteData.Success).quote)
                        finish.complete(Unit)
                        runCurrent()
                        assertSame(nextQuote, (repository.quote.value as SwapQuoteData.Success).quote)
                        verify(exactly = 0) { proposals.clear() }
                        verify(exactly = 0) { router.forward(InsufficientFundsArgs) }
                        verify(exactly = 1) { router.forward(SwapQuoteArgs) }
                    }
                    shouldContinue -> {
                        finish.complete(Unit)
                        runCurrent()
                        verify(exactly = 0) { router.forward(SwapQuoteArgs) }
                        assertNotNull(sheet.positiveButton.onClick).invoke()
                        runCurrent()
                        advanceTimeBy(350)
                        runCurrent()
                        verify(exactly = 1) { router.forward(SwapQuoteArgs) }
                        verify(exactly = 0) { router.backToRoot() }
                    }
                    else -> {
                        assertNotNull(sheet.negativeButton.onClick).invoke()
                        runCurrent()
                        if (replaceBeforeRoot) {
                            backgroundScope.launch {
                                request.requestExactOutput(amount, recipient, btc, BigDecimal("2"), { true })
                            }
                            runCurrent()
                            assertNull(repository.quote.value)
                        }
                        finish.complete(Unit)
                        runCurrent()
                        verify(exactly = 0) {
                            router.forward(SwapQuoteArgs)
                            router.forward(InsufficientFundsArgs)
                            router.forward(any<TEXUnsupportedArgs>())
                            router.forward(ErrorDialog)
                            router.backToRoot()
                        }
                        advanceTimeBy(350)
                        runCurrent()
                        verify(exactly = if (replaceBeforeRoot) 0 else 1) { router.backToRoot() }
                        assertNull(repository.quote.value)
                    }
                }
            } finally {
                finish.complete(Unit)
                pay?.viewModelScope?.cancel()
                swap?.viewModelScope?.cancel()
            }
        } finally {
            unmockkObject(FiatCurrency.USD)
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.checkPriceRefresh(pay: PayVM, repository: SwapRepositoryImpl, btc: NearSwapAsset) {
        fun form() = assertNotNull(pay.state.value)
        fun refresh(price: String) {
            val repriced = btc.copy(usdPrice = BigDecimal(price))
            repository.assets.value = repository.assets.value.copy(data = listOf(repriced))
            runCurrent()
        }
        fun assertAmounts(token: String, fiat: String) {
            assertEquals(0, BigDecimal(token).compareTo(form().amount.innerState.amount))
            assertEquals(0, BigDecimal(fiat).compareTo(form().amountFiat.innerState.amount))
        }

        refresh("2")
        form().amountFiat.onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal("100")))
        runCurrent()
        assertAmounts("50", "100")
        val stale = assertNotNull(form().primaryButton?.onClick)
        refresh("3")
        assertAmounts("33.33333333", "100")
        stale()
        runCurrent()
        assertNull(repository.quote.value)

        form().amount.onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal("50")))
        runCurrent()
        assertAmounts("50", "150")
        refresh("4")
        assertAmounts("50", "200")
    }
}
