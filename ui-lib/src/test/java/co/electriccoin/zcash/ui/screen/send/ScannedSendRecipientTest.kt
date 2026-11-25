package co.electriccoin.zcash.ui.screen.send

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.appbar.ZashiMainTopAppBarState
import co.electriccoin.zcash.ui.common.appbar.ZashiTopAppBarVM
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveABContactPickedUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveClearSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveContactByAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendData
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.time.Clock
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ScannedSendRecipientTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementsSurviveRecompositionAndRecreationOfTheScannedRoute() {
        val original = "synthetic-original-receiver"
        val scan = "synthetic-scanned-receiver"
        val contact = "synthetic-contact-receiver"
        // Public mainnet address from unified test vector row 0.
        val wallet = "u1l8xunezsvhq8fgzfl7404m450nwnd76zshscn6nfys7vyz2ywyh4cc5daaq0c" +
            "7q2su5lqfh23sp7fkf3kt27ve5948mzpfdvckzaect2jtte308mkwlycj2u0eac077wu70vqcetkxf"
        val manual = "synthetic-manual-receiver"
        val manualValidation = CompletableDeferred<Unit>()
        val synchronizer = mockk<Synchronizer> {
            coEvery { validateAddress(scan) } returns AddressType.Transparent
            coEvery { validateAddress(contact) } returns AddressType.Shielded
            coEvery { validateAddress(wallet) } returns AddressType.Unified
            coEvery { validateAddress(manual) } coAnswers {
                manualValidation.await()
                AddressType.Tex
            }
        }
        val synchronizerProvider = mockk<SynchronizerProvider> {
            coEvery { getSynchronizer() } returns synchronizer
        }
        val prefill = PrefillSendUseCase()
        val pickedRecipient = ObserveABContactPickedUseCase(synchronizerProvider)
        val account = mockk<WalletAccount>(relaxed = true) {
            every { canSpend(any()) } returns true
        }
        val accounts = mockk<GetWalletAccountsUseCase> {
            every { require() } returns flowOf(listOf(account))
        }
        val addressBook = mockk<AddressBookRepository> {
            every { observeContactByAddress(any()) } returns flowOf(null)
        }
        val proposals = mockk<ZashiProposalRepository>(relaxed = true) {
            coEvery { createProposal(any()) } returns mockk()
        }
        val createProposal = CreateProposalUseCase(
            keystoneProposalRepository = mockk(relaxed = true),
            ledgerProposalPipeline = mockk(relaxed = true),
            zashiProposalRepository = proposals,
            accountDataSource = mockk<AccountDataSource> {
                coEvery { getSelectedAccount() } returns EditAccountFixtures.zodl()
            },
            navigationRouter = mockk(relaxed = true),
        )
        var handle = SavedStateHandle()
        lateinit var send: SendViewModel
        var owner by mutableStateOf(EntryOwner())
        val rates = MutableStateFlow<ExchangeRateState>(ExchangeRateState.OptedOut)
        val appBar = mockk<ZashiTopAppBarVM>(relaxed = true) {
            every { state } returns MutableStateFlow(ZashiMainTopAppBarState.previewLoading)
        }
        val testModule = module {
            viewModel {
                SendViewModel(
                    sendEntryId = "fixture-send",
                    exchangeRateRepository = mockk<ExchangeRateRepository>(relaxed = true) {
                        every { state } returns rates
                    },
                    observeContactByAddress = ObserveContactByAddressUseCase(addressBook),
                    observeContactPicked = pickedRecipient,
                    createProposal = createProposal,
                    observeWalletAccounts = accounts,
                    navigateToSelectRecipient = mockk(),
                    navigationRouter = mockk(relaxed = true),
                    savedStateHandle = handle,
                    validateAddress = ValidateAddressUseCase(synchronizerProvider),
                ).also { send = it }
            }
            viewModel { appBar }
            single { prefill }
            single { ObserveClearSendUseCase() }
        }

        // Keep an unrelated global context present, as it can be when this test runs with the full suite.
        val ownedGlobal = if (GlobalContext.getOrNull() == null) startKoin {} else null
        val testKoin = koinApplication { modules(testModule) }
        val restorationTester = StateRestorationTester(compose)
        try {
            restorationTester.setContent {
                KoinIsolatedContext(context = testKoin) {
                    ZcashTheme {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                            WrapSend(
                                sendEntryId = "fixture-send",
                                balanceWidgetState = BalanceWidgetState.loadingPreview,
                                exchangeRateState = rates.collectAsState().value,
                                goToQrScanner = {},
                                goBack = {},
                                hasCameraFeature = false,
                                sendArguments = Send(original, cash.z.ecc.sdk.model.AddressType.UNIFIED),
                                selectedAccount = account,
                            )
                        }
                    }
                }
            }
            fun assertRecipient(expected: String, type: AddressType) {
                // The semantics query drains Robolectric's main looper, where the ViewModel collects selection events.
                compose.waitUntil(conditionDescription = "Send displays $expected", timeoutMillis = 5_000) {
                    val displayed = compose.onAllNodesWithText(expected).fetchSemanticsNodes().size == 1
                    displayed && send.recipientAddressState.value.type == type
                }
                compose.runOnIdle {
                    assertEquals(expected, send.recipientAddressState.value.address)
                    assertEquals(type, send.recipientAddressState.value.type)
                }
            }

            assertRecipient(original, AddressType.Unified)
            prefill.request(PrefillSendData.FromAddressScan(scan))
            assertRecipient(scan, AddressType.Transparent)

            pickedRecipient.onContactPicked(
                EnhancedABContact(
                    AddressBookContact("Saved recipient", contact, Instant.fromEpochSeconds(0), chain = null),
                    blockchain = null,
                )
            )
            assertRecipient(contact, AddressType.Shielded)

            pickedRecipient.onWalletAccountPicked(mockk { every { unifiedAddress } returns wallet })
            assertRecipient(wallet, AddressType.Unified)

            fun rate(price: Double) = ExchangeRateState.Data(
                isLoading = false,
                currencyConversion = FiatCurrencyConversion(Clock.System.now(), price, FiatCurrency.USD),
                onRefresh = {},
            )
            compose.runOnIdle { rates.value = rate(1_000.0) }
            val fiat = compose.onNodeWithTag(SendTag.SEND_FIAT_AMOUNT_FIELD)
            val token = compose.onNodeWithTag(SendTag.SEND_AMOUNT_FIELD)
            val review = compose.onNodeWithTag(SendTag.SEND_FORM_BUTTON)
            fun enteredAmount(input: SemanticsNodeInteraction) =
                input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.toBigDecimal()
                    .stripTrailingZeros().toPlainString()

            fiat.performTextReplacement("0.01")
            review.assertIsNotEnabled()
            coVerify(exactly = 0) { proposals.createProposal(any()) }
            fiat.performTextReplacement("0.05")
            assertEquals("0.00005", enteredAmount(token))
            review.assertIsEnabled()
            fiat.performTextReplacement("0.06")
            assertEquals("0.00005", enteredAmount(token))
            review.assertIsEnabled().performClick()
            compose.waitForIdle()
            coVerify(exactly = 1) {
                proposals.createProposal(match { it.amount == Zatoshi(5_000) && it.destination.address == wallet })
            }

            compose.runOnIdle { rates.value = rate(100.0) }
            token.performTextReplacement("184467440737.09556616")
            review.assertIsNotEnabled()
            token.performTextReplacement("21000000.00000001")
            review.assertIsNotEnabled()
            fiat.performTextReplacement("18446744073709.556616")
            review.assertIsNotEnabled()
            fiat.performTextReplacement("2100000000.000001")
            review.assertIsNotEnabled()
            coVerify(exactly = 1) { proposals.createProposal(any()) }

            token.performTextReplacement("21000000")
            review.assertIsEnabled().performClick()
            compose.waitForIdle()
            fiat.performTextReplacement("2100000000")
            review.assertIsEnabled().performClick()
            compose.waitForIdle()
            coVerify(exactly = 2) {
                proposals.createProposal(match { it.amount == Zatoshi(2_100_000_000_000_000) })
            }

            fiat.performTextReplacement("100")
            assertEquals("1", enteredAmount(token))
            compose.runOnIdle { rates.value = rate(200.0) }
            assertEquals("0.5", enteredAmount(token))
            assertEquals("100", enteredAmount(fiat))

            compose.onNodeWithText(wallet).performTextReplacement(manual)
            review.assertIsNotEnabled()
            compose.runOnIdle { manualValidation.complete(Unit) }
            assertRecipient(manual, AddressType.Tex)
            review.assertIsEnabled()
            assertEquals("0.5", enteredAmount(token))
            compose.onNodeWithText(original).assertDoesNotExist()

            val originalViewModel = send
            compose.runOnIdle {
                handle = SavedStateHandle(handle.keys().associateWith { key -> handle.get<Any>(key) })
                owner.viewModelStore.clear()
                owner = EntryOwner()
            }
            restorationTester.emulateSavedInstanceStateRestore()
            assertRecipient(manual, AddressType.Tex)
            assertNotSame(originalViewModel, send)
            compose.onNodeWithText(original).assertDoesNotExist()
            compose.runOnIdle {
                rates.value = rate(400.0).copy(
                    expectedCurrency = FiatCurrency("EUR"),
                    currencyConversion = FiatCurrencyConversion(Clock.System.now(), 400.0, FiatCurrency("EUR")),
                )
            }
            review.assertIsNotEnabled()
            assertEquals("100", enteredAmount(fiat))
            compose.runOnIdle { rates.value = rate(400.0) }
            assertEquals("0.25", enteredAmount(token))
            assertEquals("100", enteredAmount(fiat))

            token.performTextReplacement("0.4")
            compose.runOnIdle { rates.value = rate(100.0) }
            assertEquals("0.4", enteredAmount(token))
            assertEquals("40", enteredAmount(fiat))
        } finally {
            try {
                compose.runOnUiThread { owner.viewModelStore.clear() }
            } finally {
                testKoin.close()
                if (ownedGlobal != null && GlobalContext.getOrNull() === ownedGlobal.koin) stopKoin()
            }
        }
    }

    private class EntryOwner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
}
