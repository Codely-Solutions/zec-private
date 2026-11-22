package co.electriccoin.zcash.ui.screen.send

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.appbar.ZashiMainTopAppBarState
import co.electriccoin.zcash.ui.common.appbar.ZashiTopAppBarVM
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
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
        val wallet = "synthetic-wallet-receiver"
        val manual = "synthetic-manual-receiver"
        val synchronizer = mockk<Synchronizer> {
            coEvery { validateAddress(scan) } returns AddressType.Transparent
            coEvery { validateAddress(contact) } returns AddressType.Shielded
            coEvery { validateAddress(wallet) } returns AddressType.Unified
            coEvery { validateAddress(manual) } returns AddressType.Tex
        }
        val synchronizerProvider = mockk<SynchronizerProvider> {
            coEvery { getSynchronizer() } returns synchronizer
        }
        val prefill = PrefillSendUseCase()
        val pickedRecipient = ObserveABContactPickedUseCase(synchronizerProvider)
        val account = mockk<WalletAccount>(relaxed = true)
        val accounts = mockk<GetWalletAccountsUseCase> {
            every { require() } returns flowOf(listOf(account))
        }
        val addressBook = mockk<AddressBookRepository> {
            every { observeContactByAddress(any()) } returns flowOf(null)
        }
        var handle = SavedStateHandle()
        lateinit var send: SendViewModel
        var owner by mutableStateOf(EntryOwner())
        val appBar = mockk<ZashiTopAppBarVM>(relaxed = true) {
            every { state } returns MutableStateFlow(ZashiMainTopAppBarState.previewLoading)
        }
        val testModule = module {
            viewModel {
                SendViewModel(
                    exchangeRateRepository = mockk(relaxed = true),
                    observeContactByAddress = ObserveContactByAddressUseCase(addressBook),
                    observeContactPicked = pickedRecipient,
                    createProposal = mockk(),
                    observeWalletAccounts = accounts,
                    navigateToSelectRecipient = mockk(),
                    navigationRouter = mockk(relaxed = true),
                    savedStateHandle = handle,
                ).also { send = it }
            }
            viewModel { appBar }
            single { prefill }
            single { ObserveClearSendUseCase() }
            single { ValidateAddressUseCase(synchronizerProvider) }
        }

        val restorationTester = StateRestorationTester(compose)
        restorationTester.setContent {
            KoinApplication(configuration = koinConfiguration { modules(testModule) }) {
                ZcashTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        WrapSend(
                            balanceWidgetState = BalanceWidgetState.loadingPreview,
                            exchangeRateState = ExchangeRateState.OptedOut,
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
        try {
            fun assertRecipient(expected: String, type: AddressType) {
                // The semantics query drains Robolectric's main looper, where the ViewModel collects selection events.
                compose.waitUntil(conditionDescription = "Send displays $expected", timeoutMillis = 5_000) {
                    compose.onAllNodesWithText(expected).fetchSemanticsNodes().size == 1
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

            compose.onNodeWithText(wallet).performTextReplacement(manual)
            assertRecipient(manual, AddressType.Tex)
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
        } finally {
            compose.runOnIdle { owner.viewModelStore.clear() }
        }
    }

    private class EntryOwner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
}
