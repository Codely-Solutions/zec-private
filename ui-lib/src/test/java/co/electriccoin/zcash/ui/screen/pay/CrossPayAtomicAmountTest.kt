package co.electriccoin.zcash.ui.screen.pay

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.NearSwapDataSource
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.provider.NearApiProvider
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetCuratedSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPreselectedSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.IsABContactHintVisibleUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapRefundWarningUseCase
import co.electriccoin.zcash.ui.common.usecase.RequestSwapQuoteUseCase
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextFieldParser
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class CrossPayAtomicAmountTest(private val decimals: Int) {
    @Test
    fun formAndRequestAcceptOnlyRepresentablePositiveTokenAmounts() = runTest(timeout = 15.seconds) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
        try {
            val origin = SwapCatalogTestFixture.nativeZec.copy(usdPrice = BigDecimal("30"))
            val (network, recipient) = when (decimals) {
                6, 18 -> "eth" to "0xde709f2102306220921060314715629080e2fb77"
                8 -> "btc" to "1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu"
                24 -> "near" to "alice.near"
                else -> error("No pinned test asset at $decimals decimals")
            }
            val destination = SwapCatalogTestFixture.externalAssets.first {
                it.chainTicker == network && it.decimals == decimals
            }.copy(usdPrice = BigDecimal("1.00000000000000000000000000000000000001"))
            val assets = SwapAssetsData(data = listOf(destination), zecAsset = origin)
            val wireAmounts = mutableListOf<String>()
            val near = mockk<NearApiProvider> {
                coEvery { requestQuote(any()) } coAnswers {
                    val json = Json.encodeToString(QuoteRequest.serializer(), firstArg<QuoteRequest>())
                    val amount = Json.parseToJsonElement(json).jsonObject.getValue("amount").jsonPrimitive.content
                    wireAmounts.add(amount)
                    throw IllegalArgumentException("Synthetic quote provider refused request")
                }
            }
            val account = mockk<WalletAccount> {
                every { canSpend(any()) } returns true
            }
            val accountDataSource = mockk<AccountDataSource> {
                coEvery { getSelectedAccount() } returns account
                coEvery { requestNextShieldedAddress(requireFresh = true, account = account) } returns
                    "synthetic-refund-address"
            }
            val dataSource = NearSwapDataSource(near, mockk(), mockk(), mockk(), mockk())
            val swapRepository = SwapRepositoryImpl(dataSource, mockk()).also {
                it.assets.value = assets
                it.scope = backgroundScope
            }
            val router = mockk<NavigationRouter>(relaxed = true)
            val request = RequestSwapQuoteUseCase(
                navigationRouter = router,
                navigateToErrorUseCase = mockk(relaxed = true),
                swapRepository = swapRepository,
                zashiProposalRepository = mockk(),
                keystoneProposalRepository = mockk(),
                ledgerProposalPipeline = mockk(),
                accountDataSource = accountDataSource,
                synchronizerProvider = mockk(),
            )
            val vm = PayVM(
                getCuratedSwapAssetsUseCase = mockk<GetCuratedSwapAssetsUseCase> {
                    every { observe() } returns flowOf(assets)
                },
                getSelectedWalletAccount = mockk<GetSelectedWalletAccountUseCase> {
                    every { observe() } returns MutableStateFlow<WalletAccount?>(account)
                },
                swapRepository = swapRepository,
                cancelSwap = mockk(),
                navigationRouter = router,
                requestSwapQuote = request,
                navigateToSwapQuoteIfAvailable = mockk(),
                exactOutputVMMapper = ExactOutputVMMapper(),
                navigateToScanAddress = mockk(),
                navigateToSelectSwapRecipient = mockk(),
                isABContactHintVisible = mockk<IsABContactHintVisibleUseCase> {
                    every { observe(any(), any()) } returns flowOf(false)
                },
                getPreselectedSwapAsset = mockk<GetPreselectedSwapAssetUseCase> {
                    coEvery { this@mockk.invoke() } returns destination
                },
                navigateToSlippage = mockk(),
                navigateToSwapAssetPicker = mockk(),
                navigateToSwapRefundWarning = mockk<NavigateToSwapRefundWarningUseCase> {
                    coEvery { this@mockk.invoke(any(), any()) } returns true
                },
            )
            try {
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                runCurrent()
                assertNotNull(vm.state.value, "CrossPay entry state").address.onValueChange(recipient)
                runCurrent()
                val addressed = assertNotNull(vm.state.value, "CrossPay recipient state")
                assertEquals(stringRes(recipient), addressed.address.value)
                assertNull(addressed.address.error, "Recipient validation for $network")

                fun enterAmount(value: BigDecimal): PayState {
                    val raw = ZashiNumberTextFieldParser.normalizeInput(value.toPlainString())
                    val parsed = assertNotNull(ZashiNumberTextFieldParser.toBigDecimalOrNull(raw))
                    assertEquals(0, value.compareTo(parsed))
                    assertNotNull(vm.state.value).amount.onValueChange(NumberTextFieldInnerState.fromAmount(parsed))
                    runCurrent()
                    return assertNotNull(vm.state.value).also {
                        assertEquals(parsed, it.amount.innerState.amount)
                    }
                }

                val minimum = BigDecimal.ONE.movePointLeft(decimals)
                val belowMinimum = minimum.multiply(BigDecimal("0.9"))
                val fractionalTail = minimum.add(belowMinimum)

                assertFalse(assertNotNull(enterAmount(BigDecimal.ZERO).primaryButton).isEnabled)
                for (invalid in listOf(belowMinimum, fractionalTail, BigDecimal("1E+128"))) {
                    val invalidState = enterAmount(invalid)
                    assertEquals(
                        stringRes(co.electriccoin.zcash.ui.design.R.string.send_error_invalidAmount),
                        invalidState.amountError,
                    )
                    val disabledReview = assertNotNull(invalidState.primaryButton)
                    assertFalse(disabledReview.isEnabled)
                    disabledReview.onClick()
                    runCurrent()
                    request.requestExactOutput(invalid, recipient, destination, BigDecimal.ONE, { true })
                    assertFailsWith<IllegalArgumentException> {
                        dataSource.requestQuote(
                            SwapMode.EXACT_OUTPUT, invalid, "synthetic-refund", origin,
                            recipient, destination, BigDecimal.ONE,
                        )
                    }
                }
                coVerify(exactly = 0) { accountDataSource.getSelectedAccount() }
                coVerify(exactly = 0) { accountDataSource.requestNextShieldedAddress(any(), any()) }
                coVerify(exactly = 0) { near.requestQuote(any()) }

                val ordinary = enterAmount(BigDecimal("12"))
                assertNull(ordinary.amountError)
                assertTrue(assertNotNull(ordinary.primaryButton).isEnabled)

                val payable = enterAmount(minimum.setScale(decimals + 1))
                assertNull(payable.amountError)
                val review = assertNotNull(payable.primaryButton)
                assertTrue(review.isEnabled)
                review.onClick()
                runCurrent()
                assertEquals(listOf("1"), wireAmounts)

                assertNotNull(vm.state.value).amountFiat.onValueChange(
                    NumberTextFieldInnerState.fromAmount(BigDecimal.ONE)
                )
                runCurrent()
                val fiatOwned = assertNotNull(vm.state.value)
                val expected = BigDecimal.ONE.subtract(minimum)
                assertEquals(expected, fiatOwned.amount.innerState.amount)
                val fiatReview = assertNotNull(fiatOwned.primaryButton)
                assertTrue(fiatReview.isEnabled)
                fiatReview.onClick()
                runCurrent()
                assertEquals(listOf("1", expected.movePointRight(decimals).toBigIntegerExact().toString()), wireAmounts)
                coVerify(exactly = 2) { accountDataSource.requestNextShieldedAddress(true, account) }
            } finally {
                vm.viewModelScope.cancel()
            }
        } finally {
            unmockkObject(FiatCurrency.USD)
            Dispatchers.resetMain()
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} decimals")
        fun tokenDecimals() = listOf(6, 8, 18, 24)
    }
}
