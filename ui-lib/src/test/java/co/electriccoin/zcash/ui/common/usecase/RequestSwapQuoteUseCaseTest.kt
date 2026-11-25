package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouterImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.FreshShieldedAddressUnavailableException
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.FakeSwapQuote
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchException
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchType
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.SwapAssetCacheProvider
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.swap.mismatch.SwapQuoteMismatchArgs
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteArgs
import co.electriccoin.zcash.ui.screen.texunsupported.TEXUnsupportedArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertFailsWith

/**
 * Coverage of [RequestSwapQuoteUseCase]'s orchestration: for each public request method, both account
 * types on the proposal-creating paths and every failure branch (TEX-unsupported, insufficient-funds,
 * generic). Driven via the public methods with runBlocking — the use case manages its own dispatchers
 * (the NEAR data-source test uses the same pattern). Quote validation itself now lives in
 * [co.electriccoin.zcash.ui.common.repository.SwapRepository] and is covered by its test.
 *
 * Notes on cells deliberately not enumerated: flex input never creates a proposal, so it has no
 * account/failure cells; and a Zashi account never produces a TEX-unsupported error, so that cell is
 * meaningless.
 */
@Suppress("TooManyFunctions", "LargeClass")
class RequestSwapQuoteUseCaseTest {
    private val zec = SwapAssetTestFixture.zecAsset()
    private val btc = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc")

    private val navigationRouter = mockk<NavigationRouter>(relaxed = true)
    private val navigateToError = mockk<NavigateToErrorUseCase>(relaxed = true)
    private val zashiProposalRepository = mockk<ZashiProposalRepository>(relaxed = true)
    private val keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true)
    private val ledgerProposalPipeline = mockk<LedgerProposalPipeline>(relaxed = true)
    private val swapRepository = mockk<SwapRepository>(relaxed = true)
    private val accountDataSource = mockk<AccountDataSource>()
    private val synchronizer = mockk<Synchronizer>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = StandardTestDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun quoteFinishingAfterExternalNavigationCannotPrepareOrConsumeReview() = runTest(dispatcher) {
        val result = SwapQuoteData.Success(swapQuote(zec, btc))
        val instance = useCase(result)
        val active = MutableStateFlow<ActiveNavigationEntry?>(ActiveNavigationEntry("swap-entry", "Swap"))
        val responses = MutableStateFlow<SwapQuoteData?>(SwapQuoteData.Loading)
        every { navigationRouter.activeEntry } returns active
        every { swapRepository.quote } returns responses
        val request = launch {
            instance.requestExactInput(
                BigDecimal.ONE, "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", btc, BigDecimal.ONE,
                canNavigateToSwapQuote = { true }, sourceEntryId = "swap-entry"
            )
        }
        runCurrent()
        verify(exactly = 1) { swapRepository.requestExactInputQuote(any(), any(), any(), any(), any()) }
        active.value = ActiveNavigationEntry("scanner", "Scanner")
        runCurrent()
        responses.value = result
        request.join()
        assertFalse(request.isActive)
        coVerify(exactly = 0) { zashiProposalRepository.createExactInputSwapProposal(any(), any(), any()) }
        verify(exactly = 0) { swapRepository.consumeReview(any()) }
        verify(exactly = 0) { navigationRouter.custom(any()) }
    }

    @Test
    fun resultIsConsumedOnlyWhenItsExactFormReceivesTheNavigation() = runTest(dispatcher) {
        val result = SwapQuoteData.Success(flexQuote())
        val instance = useCase(result)
        val active = MutableStateFlow<ActiveNavigationEntry?>(ActiveNavigationEntry("swap-entry", "Swap"))
        val delivery = slot<NavigationCommand.ForEntry>()
        every { navigationRouter.activeEntry } returns active
        every { navigationRouter.stageForward(capture(delivery)) } returns Unit

        instance.requestFlexInputIntoZec(
            BigDecimal.ONE, "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", btc, BigDecimal.ONE,
            canNavigateToSwapQuote = { true }, sourceEntryId = "swap-entry"
        )
        val command = delivery.captured
        assertEquals("swap-entry", command.sourceEntryId)
        verify(exactly = 0) { swapRepository.consumeReview(any()) }
        assertEquals(NavigationCommand.Forward(listOf(SwapQuoteArgs)), command.buildCommand())
        verify(exactly = 1) { swapRepository.consumeReview(result) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun dismissalWaitsForProposalAndNavigationIsConsumedOnce() = runTest(dispatcher) {
        for (mode in listOf(SwapMode.EXACT_INPUT, SwapMode.EXACT_OUTPUT)) {
        val quote = swapQuote(originAsset = zec, destinationAsset = btc, mode = mode)
        val request = useCase(quote, zodl())
        val quoteFlow = MutableStateFlow<SwapQuoteData?>(SwapQuoteData.Loading)
        every { swapRepository.quote } returns quoteFlow
        val readiness = co.electriccoin.zcash.ui.common.repository.SwapRepositoryImpl(mockk(), mockk())
        readiness.assets.value = SwapAssetsData(data = listOf(btc), zecAsset = zec)
        every { swapRepository.markReviewReady(any()) } answers {
            readiness.quote.value = quoteFlow.value
            readiness.markReviewReady(firstArg())
        }
        every { swapRepository.consumeReview(any()) } answers { readiness.consumeReview(firstArg()) }
        every { swapRepository.isReviewCurrent(any()) } answers { readiness.isReviewCurrent(firstArg()) }
        every { swapRepository.requestExactInputQuote(any(), any(), any(), any(), any()) } answers {
            quoteFlow.value = SwapQuoteData.Success(quote)
        }
        every { swapRepository.requestExactOutputQuote(any(), any(), any(), any(), any()) } answers {
            quoteFlow.value = SwapQuoteData.Success(quote)
        }
        val router = NavigationRouterImpl()
        every { navigationRouter.forward(SwapQuoteArgs) } answers { router.forward(SwapQuoteArgs) }
        val commands = mutableListOf<BaseNavigationCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            router.observePipeline().collect { commands.add(it) }
        }
        val proposalStarted = CompletableDeferred<Unit>()
        val finishProposal = CompletableDeferred<Unit>()
        coEvery { zashiProposalRepository.createExactInputSwapProposal(any(), any(), any()) } coAnswers {
            proposalStarted.complete(Unit)
            finishProposal.await()
            mockk(relaxed = true)
        }
        coEvery { zashiProposalRepository.createExactOutputSwapProposal(any(), any(), any()) } coAnswers {
            proposalStarted.complete(Unit)
            finishProposal.await()
            mockk(relaxed = true)
        }
        val pendingRequest = launch {
            if (mode == SwapMode.EXACT_INPUT) request.requestExactInput(
                amount = BigDecimal.ONE,
                address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                selectedAsset = btc,
                slippage = BigDecimal("2"),
                canNavigateToSwapQuote = { true }
            ) else request.requestExactOutput(
                amount = BigDecimal.ONE,
                address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                selectedAsset = btc,
                slippage = BigDecimal("2"),
                canNavigateToSwapQuote = { true }
            )
        }
        try {
            proposalStarted.await()
            val review: BaseNavigationCommand = NavigationCommand.Forward(listOf(SwapQuoteArgs))
            // Recreate the dismissal call after the form has cleared its cancellation-sheet flag.
            launch { NavigateToSwapQuoteIfAvailableUseCase(swapRepository, router).invoke { delay(350) } }
            advanceTimeBy(350)
            runCurrent()
            assertEquals(emptyList(), commands)
            assertFalse(pendingRequest.isCompleted)

            advanceTimeBy(501)
            finishProposal.complete(Unit)
            pendingRequest.join()
            runCurrent()
            assertEquals(listOf(review), commands)
            NavigateToSwapQuoteIfAvailableUseCase(swapRepository, router).invoke { }
            assertEquals(listOf(review), commands)
            advanceTimeBy(500)
            runCurrent()
        } finally {
            finishProposal.complete(Unit)
        }
    }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun catalogDriftWhilePreparingTheProposalRejectsSwapAndPayReview() = runTest(dispatcher) {
        for (mode in listOf(SwapMode.EXACT_INPUT, SwapMode.EXACT_OUTPUT)) {
            var tokens = listOf(zec, btc)
            val quote = swapQuote(originAsset = zec, destinationAsset = btc, mode = mode)
            val source = mockk<SwapDataSource> {
                coEvery { getSupportedTokens() } answers { tokens }
                coEvery { requestQuote(any(), any(), any(), any(), any(), any(), any()) } returns quote
            }
            val cache = mockk<SwapAssetCacheProvider>(relaxed = true) { coEvery { get() } returns emptyList() }
            val repository = SwapRepositoryImpl(source, cache).also { it.scope = backgroundScope }
            repository.requestRefreshAssetsOnce()
            val request = useCase(SwapQuoteData.Success(quote), zodl(), repository)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            coEvery { zashiProposalRepository.createExactInputSwapProposal(any(), any(), any()) } coAnswers {
                started.complete(Unit)
                finish.await()
                mockk(relaxed = true)
            }
            coEvery { zashiProposalRepository.createExactOutputSwapProposal(any(), any(), any()) } coAnswers {
                started.complete(Unit)
                finish.await()
                mockk(relaxed = true)
            }
            val pending = launch { if (mode == SwapMode.EXACT_INPUT) request.exactInput() else request.exactOutput() }
            try {
                started.await()
                tokens = listOf(zec, btc, SwapAssetTestFixture.asset(usdPrice = null))
                repository.requestRefreshAssetsOnce()
                finish.complete(Unit)
                pending.join()
                assertEquals(null, repository.quote.value)
                assertEquals(null, repository.consumeReview())
                verify(exactly = 0) { navigationRouter.forward(SwapQuoteArgs) }
            } finally {
                finish.complete(Unit)
            }
        }
    }

    // region requestExactInput — proposal happy + failures

    @Test
    fun exactInputZashiCreatesProposalAndForwards() =
        runBlocking {
            useCase(swapQuote(originAsset = zec, destinationAsset = btc), zodl()).exactInput()
            coVerify { zashiProposalRepository.createExactInputSwapProposal(any(), any(), any()) }
            assertForwardedToQuote()
        }

    @Test
    fun exactInputKeystoneCreatesProposalAndPcztAndForwards() =
        runBlocking {
            useCase(swapQuote(originAsset = zec, destinationAsset = btc), keystone()).exactInput()
            coVerify { keystoneProposalRepository.createExactInputSwapProposal(any(), any(), any()) }
            coVerify { keystoneProposalRepository.createPCZTFromProposal(any()) }
            assertForwardedToQuote()
        }

    @Test
    fun exactInputLedgerCreatesProposalAndPcztAndForwards() =
        runBlocking {
            useCase(swapQuote(originAsset = zec, destinationAsset = btc), ledger()).exactInput()
            coVerify { ledgerProposalPipeline.createExactInputSwapProposal(any(), any(), any()) }
            coVerify { ledgerProposalPipeline.createPCZTFromProposal() }
            assertForwardedToQuote()
        }

    @Test
    fun exactInputZashiInsufficientFunds() =
        runBlocking {
            coEvery { zashiProposalRepository.createExactInputSwapProposal(any(), any(), any()) } throws
                InsufficientFundsException()
            useCase(swapQuote(originAsset = zec, destinationAsset = btc), zodl()).exactInput()
            assertNavigatedToInsufficientFunds()
        }

    @Test
    fun exactInputKeystoneInsufficientFunds() =
        runBlocking {
            coEvery { keystoneProposalRepository.createExactInputSwapProposal(any(), any(), any()) } throws
                InsufficientFundsException()
            useCase(swapQuote(originAsset = zec, destinationAsset = btc), keystone()).exactInput()
            assertNavigatedToInsufficientFunds()
        }

    @Test
    fun exactInputLedgerInsufficientFundsClearsEveryRepository() =
        runBlocking {
            coEvery { ledgerProposalPipeline.createExactInputSwapProposal(any(), any(), any()) } throws
                InsufficientFundsException()
            useCase(swapQuote(originAsset = zec, destinationAsset = btc), ledger()).exactInput()
            assertNavigatedToInsufficientFunds()
            coVerify(exactly = 1) { ledgerProposalPipeline.clear() }
            coVerify(exactly = 1) { keystoneProposalRepository.clear() }
            coVerify(exactly = 1) { zashiProposalRepository.clear() }
        }

    @Test
    fun exactInputKeystoneTexUnsupported() =
        runBlocking {
            coEvery { keystoneProposalRepository.createExactInputSwapProposal(any(), any(), any()) } throws
                TexUnsupportedOnKSException()
            useCase(swapQuote(originAsset = zec, destinationAsset = btc), keystone()).exactInput()
            assertNavigatedToTexUnsupported()
        }

    @Test
    fun exactInputLedgerTexUnsupportedNamesTheLedgerInTheCopy() =
        runBlocking {
            coEvery { ledgerProposalPipeline.createExactInputSwapProposal(any(), any(), any()) } throws
                TexUnsupportedOnKSException()
            useCase(swapQuote(originAsset = zec, destinationAsset = btc), ledger()).exactInput()
            assertNavigatedToTexUnsupported(isLedger = true)
            coVerify(exactly = 1) { ledgerProposalPipeline.clear() }
        }

    @Test
    fun exactInputZashiGenericError() =
        runBlocking {
            coEvery { zashiProposalRepository.createExactInputSwapProposal(any(), any(), any()) } throws TestException()
            useCase(swapQuote(originAsset = zec, destinationAsset = btc), zodl()).exactInput()
            assertNavigatedToError()
        }

    @Test
    fun exactInputKeystoneGenericError() =
        runBlocking {
            coEvery { keystoneProposalRepository.createExactInputSwapProposal(any(), any(), any()) } throws
                TestException()
            useCase(swapQuote(originAsset = zec, destinationAsset = btc), keystone()).exactInput()
            assertNavigatedToError()
        }

    @Test
    fun exactInputLedgerGenericErrorClearsEveryRepository() =
        runBlocking {
            coEvery { ledgerProposalPipeline.createExactInputSwapProposal(any(), any(), any()) } throws TestException()
            useCase(swapQuote(originAsset = zec, destinationAsset = btc), ledger()).exactInput()
            assertNavigatedToError()
            coVerify(exactly = 1) { ledgerProposalPipeline.clear() }
        }

    @Test
    fun exactInputRejectsNonIntegralProposalAmount() =
        runBlocking {
            // The zatoshi conversion happens before the account branch, so it is account-independent.
            useCase(swapQuote(originAsset = zec, destinationAsset = btc, amountIn = BigDecimal("100.5")), zodl())
                .exactInput()
            assertNavigatedToError()
        }

    // endregion
    // region requestExactOutput — proposal happy + failures

    @Test
    fun exactOutputZashiCreatesProposalAndForwards() =
        runBlocking {
            useCase(exactOutputQuote(), zodl()).exactOutput()
            coVerify { zashiProposalRepository.createExactOutputSwapProposal(any(), any(), any()) }
            assertForwardedToQuote()
        }

    @Test
    fun exactOutputKeystoneCreatesProposalAndPcztAndForwards() =
        runBlocking {
            useCase(exactOutputQuote(), keystone()).exactOutput()
            coVerify { keystoneProposalRepository.createExactOutputSwapProposal(any(), any(), any()) }
            coVerify { keystoneProposalRepository.createPCZTFromProposal(any()) }
            assertForwardedToQuote()
        }

    @Test
    fun exactOutputLedgerCreatesProposalAndPcztAndForwards() =
        runBlocking {
            useCase(exactOutputQuote(), ledger()).exactOutput()
            coVerify { ledgerProposalPipeline.createExactOutputSwapProposal(any(), any(), any()) }
            coVerify { ledgerProposalPipeline.createPCZTFromProposal() }
            assertForwardedToQuote()
        }

    @Test
    fun exactOutputZashiInsufficientFunds() =
        runBlocking {
            coEvery { zashiProposalRepository.createExactOutputSwapProposal(any(), any(), any()) } throws
                InsufficientFundsException()
            useCase(exactOutputQuote(), zodl()).exactOutput()
            assertNavigatedToInsufficientFunds()
        }

    @Test
    fun exactOutputKeystoneInsufficientFunds() =
        runBlocking {
            coEvery { keystoneProposalRepository.createExactOutputSwapProposal(any(), any(), any()) } throws
                InsufficientFundsException()
            useCase(exactOutputQuote(), keystone()).exactOutput()
            assertNavigatedToInsufficientFunds()
        }

    @Test
    fun exactOutputKeystoneTexUnsupported() =
        runBlocking {
            coEvery { keystoneProposalRepository.createExactOutputSwapProposal(any(), any(), any()) } throws
                TexUnsupportedOnKSException()
            useCase(exactOutputQuote(), keystone()).exactOutput()
            assertNavigatedToTexUnsupported()
        }

    @Test
    fun exactOutputZashiGenericError() =
        runBlocking {
            coEvery { zashiProposalRepository.createExactOutputSwapProposal(any(), any(), any()) } throws
                TestException()
            useCase(exactOutputQuote(), zodl()).exactOutput()
            assertNavigatedToError()
        }

    @Test
    fun exactOutputKeystoneGenericError() =
        runBlocking {
            coEvery { keystoneProposalRepository.createExactOutputSwapProposal(any(), any(), any()) } throws
                TestException()
            useCase(exactOutputQuote(), keystone()).exactOutput()
            assertNavigatedToError()
        }

    @Test
    fun exactOutputRejectsNonIntegralProposalAmount() =
        runBlocking {
            useCase(exactOutputQuote(amountIn = BigDecimal("100.5")), zodl()).exactOutput()
            assertNavigatedToError()
        }

    // endregion
    // region requestFlexInputIntoZec — happy (no proposal, no account branch)

    @Test
    fun flexForwardsWithoutCreatingAProposal() =
        runBlocking {
            useCase(flexQuote()).flex()
            coVerify(exactly = 0) { zashiProposalRepository.createExactInputSwapProposal(any(), any(), any()) }
            coVerify(exactly = 0) { keystoneProposalRepository.createExactInputSwapProposal(any(), any(), any()) }
            assertForwardedToQuote()
        }

    /**
     * Flex-input quotes never reach [RequestSwapQuoteUseCase.createProposal] through the public
     * flex entry point (it never asks for one), but a Ledger account's branch still guards against
     * one arriving with `createProposal = true` by throwing - the same defensive arm proposal
     * creation has always had for Keystone and Zashi.
     */
    @Test
    fun aFlexModeQuoteOnLedgerWithProposalCreationRequestedThrowsAndClearsEveryRepository() =
        runBlocking {
            useCase(flexQuote(), ledger()).exactInput()
            assertNavigatedToError()
            coVerify(exactly = 0) { ledgerProposalPipeline.createExactInputSwapProposal(any(), any(), any()) }
            coVerify(exactly = 1) { ledgerProposalPipeline.clear() }
            coVerify(exactly = 1) { keystoneProposalRepository.clear() }
            coVerify(exactly = 1) { zashiProposalRepository.clear() }
        }

    @Test
    fun exactInputMismatchOpensTheMismatchSheetInsteadOfTheQuote() =
        runBlocking {
            useCase(mismatchError(SwapMode.EXACT_INPUT, SwapQuoteMismatchType.REQUESTED_AMOUNT)).exactInput()

            verify(exactly = 1) { swapRepository.clearQuote() }
            verify {
                navigationRouter.forward(
                    match<SwapQuoteMismatchArgs> {
                        it.mismatchType == SwapQuoteMismatchType.REQUESTED_AMOUNT &&
                            it.mode == SwapMode.EXACT_INPUT &&
                            it.provider == "near" &&
                            it.originTokenTicker == zec.tokenTicker &&
                            it.originChainTicker == zec.chainTicker &&
                            it.destinationTokenTicker == btc.tokenTicker &&
                            it.destinationChainTicker == btc.chainTicker &&
                            it.depositAddress == "deposit-address"
                    }
                )
            }
            verify(exactly = 0) { navigationRouter.forward(SwapQuoteArgs) }
            verify(exactly = 0) { navigateToError(any(), any()) }
        }

    @Test
    fun flexInputMismatchReportsTheSelectedAssetAsTheOrigin() =
        runBlocking {
            useCase(
                mismatchError(
                    mode = SwapMode.FLEX_INPUT,
                    type = SwapQuoteMismatchType.REFUND_ADDRESS,
                    origin = btc,
                    destination = zec
                )
            ).flex()

            verify {
                navigationRouter.forward(
                    match<SwapQuoteMismatchArgs> {
                        it.mismatchType == SwapQuoteMismatchType.REFUND_ADDRESS &&
                            it.originTokenTicker == btc.tokenTicker &&
                            it.destinationTokenTicker == zec.tokenTicker
                    }
                )
            }
            verify(exactly = 0) { navigationRouter.forward(SwapQuoteArgs) }
        }

    /**
     * With the cancel sheet up there is nowhere to navigate, so the rejection stays in the repository's
     * quote — exactly like every other quote error — and surfaces once the sheet is dismissed.
     */
    @Test
    fun mismatchErrorIsKeptWhenTheSwapScreenIsGone() =
        runBlocking {
            useCase(mismatchError(SwapMode.EXACT_INPUT, SwapQuoteMismatchType.SWAP_TYPE))
                .requestExactInput(
                    amount = BigDecimal("1"),
                    address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                    selectedAsset = btc,
                    slippage = BigDecimal("2"),
                    canNavigateToSwapQuote = { false }
                )

            verify(exactly = 0) { swapRepository.clearQuote() }
            verify(exactly = 0) { navigationRouter.forward(ofType<SwapQuoteMismatchArgs>()) }
        }

    /**
     * Only a rejection carrying the report context opens the mismatch sheet. One without it cannot be
     * built by the repository, so reaching this would be a routing bug: the request falls back to the
     * generic quote-error path instead of the sheet, and the rejection is reported to crash monitoring.
     */
    @Test
    fun mismatchWithoutReportContextFallsBackToTheGenericQuoteError() =
        runBlocking {
            useCase(
                SwapQuoteData.Error(
                    mode = SwapMode.EXACT_INPUT,
                    exception =
                        SwapQuoteMismatchException.Rejected(
                            type = SwapQuoteMismatchType.SWAP_TYPE,
                            message = "mismatch"
                        )
                )
            ).exactInput()

            verify(exactly = 0) { navigationRouter.forward(ofType<SwapQuoteMismatchArgs>()) }
            verify { navigationRouter.forward(SwapQuoteArgs) }
        }

    /** A plain quote failure is surfaced by the swap screen itself, not by the mismatch sheet. */
    @Test
    fun nonMismatchQuoteErrorsStillFollowTheOldPath() =
        runBlocking {
            useCase(SwapQuoteData.Error(SwapMode.EXACT_INPUT, TestException())).exactInput()

            verify(exactly = 0) { navigationRouter.forward(ofType<SwapQuoteMismatchArgs>()) }
            verify { navigationRouter.forward(SwapQuoteArgs) }
        }

    // endregion
    @Test
    fun incomingQuotesUseIndependentShieldedRecipients() =
        runBlocking {
            val instance = useCase(SwapQuoteData.Error(SwapMode.FLEX_INPUT, TestException()))
            coEvery { accountDataSource.requestNextShieldedAddress(requireFresh = true, account = any()) } returnsMany
                listOf("shielded-first", "shielded-second")
            instance.flex()
            instance.flex()
            coVerify(exactly = 1) {
                swapRepository.requestFlexInputIntoZec(
                    amount = BigDecimal("1"),
                    refundAddress = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                    destinationAddress = "shielded-first",
                    originAsset = btc,
                    slippage = BigDecimal("2"),
                    owner = any(),
                )
            }
            coVerify(exactly = 1) {
                swapRepository.requestFlexInputIntoZec(
                    amount = BigDecimal("1"),
                    refundAddress = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                    destinationAddress = "shielded-second",
                    originAsset = btc,
                    slippage = BigDecimal("2"),
                    owner = any(),
                )
            }
        }

    @Test
    fun outgoingQuotesUseIndependentShieldedRefunds() =
        runBlocking {
            val instance = useCase(SwapQuoteData.Error(SwapMode.EXACT_INPUT, TestException()))
            coEvery { accountDataSource.requestNextShieldedAddress(requireFresh = true, account = any()) } returnsMany
                listOf("refund-first", "refund-second")
            instance.exactInput()
            instance.exactInput()
            coVerify(exactly = 1) {
                swapRepository.requestExactInputQuote(
                    amount = BigDecimal("1"),
                    address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                    refundAddress = "refund-first",
                    destinationAsset = btc,
                    slippage = BigDecimal("2")
                )
            }
            coVerify(exactly = 1) {
                swapRepository.requestExactInputQuote(
                    amount = BigDecimal("1"),
                    address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                    refundAddress = "refund-second",
                    destinationAsset = btc,
                    slippage = BigDecimal("2")
                )
            }
        }

    @Test
    fun addressGenerationFailureNeverRequestsASwapQuote() =
        runBlocking {
            val instance = useCase(SwapQuoteData.Error(SwapMode.EXACT_INPUT, TestException()))
            coEvery { accountDataSource.requestNextShieldedAddress(requireFresh = true, account = any()) } throws
                FreshShieldedAddressUnavailableException()
            instance.exactInput()
            instance.exactOutput()
            instance.flex()
            verify(exactly = 0) {
                swapRepository.requestExactInputQuote(any(), any(), any(), any(), any())
                swapRepository.requestExactOutputQuote(any(), any(), any(), any(), any())
                swapRepository.requestFlexInputIntoZec(any(), any(), any(), any(), any(), any())
            }
            verify(exactly = 3) { navigateToError(any(), any()) }
        }

    @Test
    fun rejectedCompositionCannotReplaceTheCancellationConfirmation() = runBlocking {
        val invalid = object : SwapQuote by swapQuote(zec, btc) {
            override val provider: String = "unsupported"
        }
        useCase(invalid).requestExactInput(
            amount = BigDecimal.ONE,
            address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
            selectedAsset = btc,
            slippage = BigDecimal("2"),
            canNavigateToSwapQuote = { false }
        )

        verify(exactly = 0) { swapRepository.clearQuote() }
        verify(exactly = 0) { navigateToError(any(), any()) }
        verify(exactly = 0) { navigationRouter.forward(SwapQuoteArgs) }
        coVerify(exactly = 0) { zashiProposalRepository.createExactInputSwapProposal(any(), any(), any()) }
    }

    // region helpers

    @Test
    fun invalidRecipientsStopEveryModeBeforeFreshAddressOrProvider() = runBlocking {
        val instance = useCase(SwapQuoteData.Error(SwapMode.EXACT_INPUT, TestException()))
        instance.requestExactInput(BigDecimal.ONE, "invalid private value", btc, BigDecimal.ONE, { true })
        instance.requestExactOutput(BigDecimal.ONE, "invalid private value", btc, BigDecimal.ONE, { true })
        instance.requestFlexInputIntoZec(BigDecimal.ONE, "invalid private value", btc, BigDecimal.ONE, { true })
        coVerify(exactly = 0) { accountDataSource.requestNextShieldedAddress(any()) }
        verify(exactly = 0) {
            swapRepository.requestExactInputQuote(any(), any(), any(), any(), any())
            swapRepository.requestExactOutputQuote(any(), any(), any(), any(), any())
            swapRepository.requestFlexInputIntoZec(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 3) {
            navigateToError(
                match<ErrorArgs.General> { it.exception.message == "Invalid swap recipient for selected asset" }, any()
            )
        }
    }

    @Test
    fun contactNetworkMismatchStopsBeforeFreshAddress() = runBlocking {
        val instance = useCase(SwapQuoteData.Error(SwapMode.EXACT_INPUT, TestException()))
        instance.requestExactInput(
            BigDecimal.ONE, "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", btc, BigDecimal.ONE, { true },
            contactNetwork = "eth"
        )
        coVerify(exactly = 0) { accountDataSource.requestNextShieldedAddress(any()) }
        verify(exactly = 0) { swapRepository.requestExactInputQuote(any(), any(), any(), any(), any()) }
        assertNavigatedToError()
    }

    @Test
    fun bitcoinPaymentRequestUsesEditedOutputAmountAndNormalizedRecipient() = runBlocking {
        val instance = useCase(SwapQuoteData.Error(SwapMode.EXACT_OUTPUT, TestException()))
        instance.requestExactOutput(
            BigDecimal("0.75"), "bitcoin:1BoatSLRHtKNngkdXEeobR76b53LETtpyT?amount=0.5", btc, BigDecimal.ONE, { true }
        )
        verify {
            swapRepository.requestExactOutputQuote(
                BigDecimal("0.75"), "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", "deposit", btc, BigDecimal.ONE
            )
        }
    }

    @Test
    fun bitcoinRequiredFieldAndIncompatibleAmountModesStopBeforeProvider() = runBlocking {
        val instance = useCase(SwapQuoteData.Error(SwapMode.EXACT_OUTPUT, TestException()))
        val request = "bitcoin:1BoatSLRHtKNngkdXEeobR76b53LETtpyT?amount=0.5"
        instance.requestExactOutput(BigDecimal.ONE, "$request&req-unsupported=1", btc, BigDecimal.ONE, { true })
        instance.requestExactInput(BigDecimal.ONE, request, btc, BigDecimal.ONE, { true })
        instance.requestFlexInputIntoZec(BigDecimal.ONE, request, btc, BigDecimal.ONE, { true })
        coVerify(exactly = 0) { accountDataSource.requestNextShieldedAddress(any()) }
        verify(exactly = 0) {
            swapRepository.requestExactOutputQuote(any(), any(), any(), any(), any())
            swapRepository.requestExactInputQuote(any(), any(), any(), any(), any())
            swapRepository.requestFlexInputIntoZec(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun paymentUriIsNormalizedBeforeQuoteRequest() = runBlocking {
        val instance = useCase(SwapQuoteData.Error(SwapMode.EXACT_INPUT, TestException()))
        instance.requestExactInput(
            BigDecimal.ONE, " bitcoin:1BoatSLRHtKNngkdXEeobR76b53LETtpyT ", btc, BigDecimal.ONE, { true }
        )
        verify {
            swapRepository.requestExactInputQuote(
                BigDecimal.ONE, "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", "deposit", btc, BigDecimal.ONE
            )
        }
    }

    @Test
    fun proposalCancellationPropagatesWithoutErrorNavigation() = runBlocking {
        coEvery { zashiProposalRepository.createExactInputSwapProposal(any(), any(), any()) } throws
            CancellationException()
        val instance = useCase(swapQuote(zec, btc))
        assertFailsWith<CancellationException> { instance.exactInput() }
        verify(exactly = 0) { navigateToError(any(), any()) }
        verify(exactly = 0) { navigationRouter.forward(SwapQuoteArgs) }
    }

    @Test
    fun invalidZcashSdkRecipientStopsBeforeFreshAddressOrQuote() = runBlocking {
        val instance = useCase(SwapQuoteData.Error(SwapMode.EXACT_INPUT, TestException()))
        coEvery { synchronizer.validateAddress(any()) } returns AddressType.Invalid("private native reason")
        val recipient =
            "u1l8xunezsvhq8fgzfl7404m450nwnd76zshscn6nfys7vyz2ywyh4cc5daaq0c7q2su5lqfh23sp7fkf3kt27ve" +
                "5948mzpfdvckzaect2jtte308mkwlycj2u0eac077wu70vqcetkxf"
        instance.requestExactInput(BigDecimal.ONE, recipient, zec, BigDecimal.ONE, { true })
        coVerify(exactly = 1) { synchronizer.validateAddress(recipient) }
        coVerify(exactly = 0) { accountDataSource.requestNextShieldedAddress(any()) }
        verify(exactly = 0) { swapRepository.requestExactInputQuote(any(), any(), any(), any(), any()) }
        assertNavigatedToError()
    }

    private fun mismatchError(
        mode: SwapMode,
        type: SwapQuoteMismatchType,
        origin: SwapAsset = zec,
        destination: SwapAsset = btc
    ) = SwapQuoteData.Error(
        mode = mode,
        exception =
            SwapQuoteMismatchException.Reported(
                type = type,
                message = "mismatch",
                depositAddress = "deposit-address",
                provider = "near",
                originAsset = origin,
                destinationAsset = destination
            )
    )

    private fun assertForwardedToQuote() {
        verify { navigationRouter.forward(SwapQuoteArgs) }
        verify(exactly = 0) { navigateToError(any(), any()) }
    }

    private fun assertNavigatedToError() {
        verify { navigateToError(any(), any()) }
        verify(exactly = 0) { navigationRouter.forward(SwapQuoteArgs) }
    }

    private fun assertNavigatedToInsufficientFunds() {
        verify { navigationRouter.forward(InsufficientFundsArgs) }
        verify(exactly = 0) { navigationRouter.forward(SwapQuoteArgs) }
    }

    private fun assertNavigatedToTexUnsupported(isLedger: Boolean = false) {
        verify { navigationRouter.forward(TEXUnsupportedArgs(isLedger = isLedger)) }
        verify(exactly = 0) { navigationRouter.forward(SwapQuoteArgs) }
    }

    @Test
    fun providerDelayCannotBindAnOutgoingRefundToOneAccountAndDebitAnother() = runTest {
        for (mode in listOf(SwapMode.EXACT_INPUT, SwapMode.EXACT_OUTPUT)) {
            val first = EditAccountFixtures.zodl()
            val second = EditAccountFixtures.keystone()
            var account: WalletAccount = first
            val quote = swapQuote(originAsset = zec, destinationAsset = btc, mode = mode)
            val request = useCase(quote, first)
            coEvery { accountDataSource.getSelectedAccount() } answers { account }
            val result = MutableStateFlow<SwapQuoteData?>(SwapQuoteData.Loading)
            every { swapRepository.quote } returns result
            val entered = CompletableDeferred<Unit>()
            coEvery { swapRepository.requestExactInputQuote(any(), any(), any(), any(), any()) } answers {
                entered.complete(Unit)
            }
            coEvery { swapRepository.requestExactOutputQuote(any(), any(), any(), any(), any()) } answers {
                entered.complete(Unit)
            }
            val job = launch {
                if (mode == SwapMode.EXACT_INPUT) request.exactInput() else request.exactOutput()
            }

            entered.await()
            account = second
            result.value = SwapQuoteData.Success(quote)
            job.join()

            coVerify { accountDataSource.requestNextShieldedAddress(true, first) }
            coVerify(exactly = 0) { zashiProposalRepository.createExactInputSwapProposal(any(), any(), any()) }
            coVerify(exactly = 0) { keystoneProposalRepository.createExactInputSwapProposal(any(), any(), any()) }
            coVerify(exactly = 0) { zashiProposalRepository.createExactOutputSwapProposal(any(), any(), any()) }
            coVerify(exactly = 0) { keystoneProposalRepository.createExactOutputSwapProposal(any(), any(), any()) }
        }
        verify(exactly = 0) { navigationRouter.forward(SwapQuoteArgs) }
    }

    @Test
    fun crossPayCannotOpenReviewWhenTheSelectionIsLoadingAfterDismissal() = runTest {
        val account = zodl()
        val quote = swapQuote(originAsset = zec, destinationAsset = btc, mode = SwapMode.EXACT_OUTPUT)
        val request = useCase(quote, account)
        val accounts = MutableStateFlow<List<WalletAccount>?>(listOf(account))
        every { accountDataSource.allAccounts } returns accounts
        coEvery { zashiProposalRepository.createExactOutputSwapProposal(any(), any(), account) } returns mockk()
        val waiting = CompletableDeferred<Unit>()
        val dismissed = CompletableDeferred<Unit>()
        val job = launch {
            request.requestExactOutput(
                amount = BigDecimal.ONE,
                address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                selectedAsset = btc,
                slippage = BigDecimal("2"),
                canNavigateToSwapQuote = {
                    waiting.complete(Unit)
                    dismissed.await()
                    true
                },
            )
        }
        waiting.await()
        accounts.value = null
        dismissed.complete(Unit)
        job.join()

        verify(exactly = 0) { swapRepository.markReviewReady(any()) }
        verify { zashiProposalRepository.clear() }
        assertNavigatedToError()
    }

    private fun zodl(): WalletAccount = mockk<ZodlAccount> {
        every { isSpendable } returns true
        every { isSelected } returns true
    }

    private fun keystone(): WalletAccount = mockk<KeystoneAccount> {
        every { isSpendable } returns true
        every { isSelected } returns true
    }

    private fun ledger(): WalletAccount = mockk<LedgerAccount> {
        every { isSpendable } returns true
        every { isSelected } returns true
    }

    private suspend fun RequestSwapQuoteUseCase.exactInput(selectedAsset: SwapAsset = btc) =
        requestExactInput(
            amount = BigDecimal("1"),
            address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
            selectedAsset = selectedAsset,
            slippage = BigDecimal("2"),
            canNavigateToSwapQuote = { true }
        )

    private suspend fun RequestSwapQuoteUseCase.exactOutput(selectedAsset: SwapAsset = btc) =
        requestExactOutput(
            amount = BigDecimal("1"),
            address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
            selectedAsset = selectedAsset,
            slippage = BigDecimal("2"),
            canNavigateToSwapQuote = { true }
        )

    private suspend fun RequestSwapQuoteUseCase.flex(selectedAsset: SwapAsset = btc) =
        requestFlexInputIntoZec(
            amount = BigDecimal("1"),
            refundAddress = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
            selectedAsset = selectedAsset,
            slippage = BigDecimal("2"),
            canNavigateToSwapQuote = { true }
        )

    private suspend fun useCase(
        swapQuote: SwapQuote,
        selectedAccount: WalletAccount = zodl()
    ): RequestSwapQuoteUseCase = useCase(SwapQuoteData.Success(swapQuote), selectedAccount)

    private suspend fun useCase(
        quoteData: SwapQuoteData,
        selectedAccount: WalletAccount = zodl(),
        repository: SwapRepository = swapRepository,
    ): RequestSwapQuoteUseCase {
        every { swapRepository.quote } returns MutableStateFlow(quoteData)
        every { swapRepository.markReviewReady(any()) } returns true
        every { swapRepository.consumeReview(any()) } returns quoteData
        every { swapRepository.assets } returns
            MutableStateFlow(SwapAssetsData(data = listOf(btc), zecAsset = zec))

        val shieldedAddress = "deposit"
        coEvery { synchronizer.validateAddress(any()) } returns AddressType.Unified
        val synchronizerProvider = mockk<SynchronizerProvider> {
            coEvery { getSynchronizer() } returns this@RequestSwapQuoteUseCaseTest.synchronizer
        }
        coEvery {
            accountDataSource.requestNextShieldedAddress(requireFresh = true, account = any())
        } returns shieldedAddress
        coEvery { accountDataSource.getSelectedAccount() } returns selectedAccount
        every { accountDataSource.allAccounts } returns MutableStateFlow(listOf(selectedAccount))
        return RequestSwapQuoteUseCase(
            navigationRouter = navigationRouter,
            navigateToErrorUseCase = navigateToError,
            swapRepository = repository,
            zashiProposalRepository = zashiProposalRepository,
            keystoneProposalRepository = keystoneProposalRepository,
            ledgerProposalPipeline = ledgerProposalPipeline,
            accountDataSource = accountDataSource,
            synchronizerProvider = synchronizerProvider
        )
    }

    /** A valid exact-input quote (ZEC -> btc) unless overridden to trip a specific check. */
    @Suppress("LongParameterList")
    private fun swapQuote(
        originAsset: SwapAsset,
        destinationAsset: SwapAsset,
        amountInFormatted: BigDecimal = BigDecimal("1"),
        amountOutFormatted: BigDecimal = BigDecimal("1"),
        amountIn: BigDecimal = BigDecimal("100"),
        mode: SwapMode = SwapMode.EXACT_INPUT,
        depositAddress: String = "deposit",
        destinationAddress: String = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
        refundAddress: String = "deposit"
    ): SwapQuote =
        // A hand-written fake, not a mock: mockk hangs when a stubbed member returns a @JvmInline
        // value class, and every SwapAddress implementation (DynamicSwapAddress, ...) is one.
        FakeSwapQuote(
            originAsset = originAsset,
            destinationAsset = destinationAsset,
            mode = mode,
            amountIn = amountIn,
            amountInFormatted = amountInFormatted,
            amountOutFormatted = amountOutFormatted,
            depositAddress = depositAddress,
            destinationAddress = destinationAddress,
            refundAddress = refundAddress
        )

    /** A valid exact-output quote (ZEC -> btc, EXACT_OUTPUT mode). */
    private fun exactOutputQuote(
        originAsset: SwapAsset = zec,
        destinationAsset: SwapAsset = btc,
        amountOutFormatted: BigDecimal = BigDecimal("1"),
        amountIn: BigDecimal = BigDecimal("100"),
        destinationAddress: String = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
        refundAddress: String = "deposit"
    ): SwapQuote =
        swapQuote(
            originAsset = originAsset,
            destinationAsset = destinationAsset,
            amountOutFormatted = amountOutFormatted,
            amountIn = amountIn,
            mode = SwapMode.EXACT_OUTPUT,
            destinationAddress = destinationAddress,
            refundAddress = refundAddress
        )

    /** A valid flex-input quote (btc -> ZEC): selected asset is the origin, ZEC is the destination. */
    private fun flexQuote(
        originAsset: SwapAsset = btc,
        destinationAsset: SwapAsset = zec,
        amountInFormatted: BigDecimal = BigDecimal("1"),
        refundAddress: String = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
        destinationAddress: String = "deposit"
    ): SwapQuote =
        swapQuote(
            originAsset = originAsset,
            destinationAsset = destinationAsset,
            amountInFormatted = amountInFormatted,
            mode = SwapMode.FLEX_INPUT,
            refundAddress = refundAddress,
            destinationAddress = destinationAddress
        )

    private class TestException : Exception()

    // endregion
}
