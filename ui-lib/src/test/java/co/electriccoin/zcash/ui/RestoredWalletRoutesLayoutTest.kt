package co.electriccoin.zcash.ui

import android.app.Activity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.VKType
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarNavigationTag
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.KeyboardManager
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.error.AndroidErrorBottomSheet
import co.electriccoin.zcash.ui.screen.error.AndroidErrorDialog
import co.electriccoin.zcash.ui.screen.error.ErrorBottomSheet
import co.electriccoin.zcash.ui.screen.error.ErrorDialog
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.error.SyncErrorArgs
import co.electriccoin.zcash.ui.screen.error.SyncErrorScreen
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.exportvk.detail.VKDetailArgs
import co.electriccoin.zcash.ui.screen.exportvk.detail.VKDetailScreenContent
import co.electriccoin.zcash.ui.screen.qrcode.QrCodeScreenContent
import co.electriccoin.zcash.ui.screen.qrcode.QrCodeState
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType
import co.electriccoin.zcash.ui.screen.request.RequestArgs
import co.electriccoin.zcash.ui.screen.request.RequestScreenContent
import co.electriccoin.zcash.ui.screen.request.model.RequestState
import co.electriccoin.zcash.ui.screen.scankeystone.KeystoneSignatureScanner
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystonePCZTRequest
import co.electriccoin.zcash.ui.screen.scankeystone.viewmodel.ScanKeystonePCZTViewModel
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionScreen
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionVM
import co.electriccoin.zcash.ui.screen.swap.SwapCancelState
import co.electriccoin.zcash.ui.screen.swap.SwapCancelView
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationArgs
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationScreen
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationVM
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteArgs
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteVM
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteView
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.KoinApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp")
class RestoredWalletRoutesLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var controller: NavHostController
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private val context get() = RuntimeEnvironment.getApplication()
    private val submit = mockk<SubmitProposalUseCase>(relaxed = true)
    private val cancelQuote = mockk<CancelSwapQuoteUseCase>(relaxed = true)
    private val router = mockk<NavigationRouter>(relaxed = true)

    @Test
    fun restoredErrorDialogOffersClose() = assertErrorRecovery(ErrorDialog("old-dialog"))

    @Test
    fun restoredErrorSheetOffersClose() = assertErrorRecovery(ErrorBottomSheet("old-sheet"))

    @Test
    fun restoredSyncErrorOffersClose() = assertErrorRecovery(SyncErrorArgs("old-sync"))

    private fun assertErrorRecovery(route: Any) {
        install()
        compose.runOnIdle { controller.navigate(route) }
        val heading = compose.onNodeWithText(context.getString(R.string.private_error_unavailable_title))
        heading.assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.general_close), ignoreCase = true).assertIsEnabled()
    }

    @Test
    @Suppress("DEPRECATION")
    fun restoredQuoteDisplaysRecoveryAndHandlesBackFromItsDialog() {
        val accounts = emptyAccounts()
        val swaps = emptySwaps()
        var backs = 0
        every { router.back() } answers { backs++ }
        compose.setContent {
            ZcashTheme {
                controller = rememberNavController()
                val vm = remember {
                    quoteViewModel(accounts, swaps, mockk())
                }
                NavHost(controller, startDestination = HomeArgs) {
                    composable<HomeArgs> { Text("Wallet home") }
                    dialogComposable<SwapQuoteArgs> { SwapQuoteView(vm.state.value) }
                }
            }
        }
        compose.onNodeWithText("Wallet home").assertIsDisplayed()
        compose.runOnIdle { controller.navigate(SwapQuoteArgs) }
        compose.onNodeWithText(context.getString(R.string.private_quote_unavailable_message)).assertIsDisplayed()
        compose.runOnIdle {
            requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed()
            assertEquals(1, backs)
        }
        coVerify(exactly = 0) { submit(any()) }
        verify(exactly = 0) { cancelQuote() }
    }

    @Test
    fun restoredKeystoneOffersBackAtLargeText() {
        install()
        compose.runOnIdle { controller.navigate(SignKeystoneTransactionArgs) }
        compose.onNodeWithText(context.getString(R.string.private_keystone_unavailable_message)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.general_back), ignoreCase = true).assertIsEnabled()
    }

    @Test
    fun expiredViewingKeyReviewOffersBackAtLargeText() {
        install()
        val route = VKDetailArgs(VKType.INCOMING, "expired-review")
        compose.runOnIdle { controller.navigate(route) }
        compose.onNodeWithText(context.getString(R.string.private_viewing_key_unavailable_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.general_back), ignoreCase = true).performClick()
        compose.onNodeWithText("Wallet home").assertIsDisplayed()

        compose.runOnIdle { controller.navigate(route) }
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithText("Wallet home").assertIsDisplayed()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun guardedForwardChecksTheActualDestinationAfterKeyboardClose() {
        install()
        compose.runOnIdle {
            runTest {
                val keyboard = KeyboardManager(isOpen = true, rootSoftwareKeyboardController = null)
                val navigator = NavigatorImpl(mockk<Activity>(), controller, keyboard, mockk(), mockk(), mockk())
                val originalEntry = checkNotNull(controller.currentBackStackEntry).id
                val request = RequestArgs(ReceiveAddressType.Unified.ordinal)
                val command = CustomNavigationCommand { NavigationCommand.Forward(listOf(request), originalEntry) }
                val pending = launch { navigator.executeCommand(command) }
                runCurrent()
                assertTrue(pending.isActive)
                controller.navigate(SignKeystoneTransactionArgs)
                keyboard.onKeyboardClosed()
                pending.join()
                assertEquals(
                    SignKeystoneTransactionArgs,
                    controller.currentBackStackEntry?.toRoute<SignKeystoneTransactionArgs>()
                )

                assertTrue(controller.popBackStack())
                navigator.executeCommand(command)
                assertEquals(request, controller.currentBackStackEntry?.toRoute<RequestArgs>())
            }
        }
    }

    @Test
    fun restoredKeystoneSignatureRouteOffersBackEvenWithTheWalletReady() {
        install()
        compose.runOnIdle {
            controller.navigate(SignKeystoneTransactionArgs)
            controller.navigate(ScanKeystonePCZTRequest("old-preparation"))
        }
        compose.onNodeWithText(context.getString(R.string.private_keystone_unavailable_message)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.general_back), ignoreCase = true).performClick()
        compose.runOnIdle {
            assertEquals(
                SignKeystoneTransactionArgs, controller.currentBackStackEntry?.toRoute<SignKeystoneTransactionArgs>()
            )
        }
    }

    @Test
    fun restoredRequestCanExitWhileItsAddressIsStillLoading() = assertLoadingBack(
        label = context.getString(co.electriccoin.zcash.ui.design.R.string.general_back),
        taggedBack = true,
    ) { navigate(RequestArgs(ReceiveAddressType.Unified.ordinal)) }

    @Test
    fun restoredReceiveQrCanExitWithoutAnAvailableAddress() = assertLoadingBack(
        label = context.getString(R.string.general_close),
        taggedBack = false,
    ) { navigate("${NavigationTargets.QR_CODE}/${ReceiveAddressType.Sapling.ordinal}") }

    private fun assertLoadingBack(label: String, taggedBack: Boolean, open: NavHostController.() -> Unit) {
        var backs = 0
        install { backs++ }
        compose.runOnIdle { controller.open() }
        val back = compose.onNodeWithContentDescription(label).assertIsDisplayed()
        val control = if (taggedBack) compose.onNodeWithTag(ZashiTopAppBarNavigationTag.BACK) else back
        control.assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { action -> assertTrue(action()) }
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals(HomeArgs, controller.currentBackStackEntry?.toRoute<HomeArgs>())
        }

        compose.runOnIdle { controller.open() }
        compose.onNodeWithContentDescription(label).assertIsDisplayed()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.runOnIdle {
            assertEquals(2, backs)
            assertEquals(HomeArgs, controller.currentBackStackEntry?.toRoute<HomeArgs>())
        }
    }

    @Test
    fun quoteCancellationKeepsTheDecisionVisibleAndReportsActualHide() {
        val sheet = mutableStateOf<SwapCancelState?>(null)
        var hidden = 0
        val continueQuote = { sheet.value = null }
        sheet.value = SwapCancelState(
            icon = imageRes(R.drawable.ic_swap_quote_cancel),
            title = stringRes("Cancel this quote?"),
            subtitle = stringRes("Synthetic quote"),
            negativeButton = ButtonState(text = stringRes("Cancel quote"), onClick = {}),
            positiveButton = ButtonState(text = stringRes("Continue quote"), onClick = continueQuote),
            onBack = continueQuote,
        )
        compose.setContent {
            ZcashTheme { SwapCancelView(sheet.value) { hidden++ } }
        }
        val choice = compose.onNodeWithText("Continue quote").assertIsDisplayed()
        compose.onNodeWithText("Cancel this quote?").performTouchInput { swipeDown() }
        choice.assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, hidden) }
        choice.performSemanticsAction(SemanticsActions.OnClick) { action -> assertTrue(action()) }
        choice.assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, hidden) }
    }

    @Test
    fun restoredDepositOffersActivityAtLargeText() {
        install()
        compose.runOnIdle { controller.navigate(ORSwapConfirmationArgs) }
        compose.onNodeWithText(context.getString(R.string.private_deposit_unavailable_message)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.private_recovery_open_activity)).assertIsEnabled()
        coVerify(exactly = 0) { submit(any()) }
    }

    private fun emptyAccounts() = mockk<AccountDataSource> {
        every { selectedAccount } returns flowOf(null)
        every { allAccounts } returns MutableStateFlow(null)
    }

    private fun emptySwaps() = mockk<SwapRepository> {
        every { quote } returns MutableStateFlow<SwapQuoteData?>(null)
    }

    private fun quoteViewModel(accounts: AccountDataSource, swaps: SwapRepository, keys: KeystoneProposalRepository) =
        SwapQuoteVM(
            ObserveProposalUseCase(keys, mockk(), mockk(), accounts),
            ObserveSelectedWalletAccountUseCase(accounts), mockk(), swaps,
            cancelQuote, mockk(), mockk(), submit, router, mockk(),
        )

    private fun install(onLoadingBack: () -> Unit = {}) {
        val accounts = emptyAccounts()
        val swaps = emptySwaps()
        val keys = mockk<KeystoneProposalRepository> {
            every { transactionProposal } returns MutableStateFlow(null)
        }
        val dependencies = module {
            single<NavigationRouter> { router }
            single { NavigateToErrorUseCase(router) }
            viewModel {
                ORSwapConfirmationVM(swaps, cancelQuote, mockk(), router, mockk(), mockk(), AppAccessSession())
            }
            viewModel {
                SignKeystoneTransactionVM(GetWalletAccountsUseCase(accounts), keys, router, mockk(), mockk(), mockk())
            }
            viewModel {
                val fresh = KeystoneProposalRepositoryImpl(accounts, mockk(), mockk(), mockk())
                ScanKeystonePCZTViewModel(mockk(), get(), fresh)
            }
        }
        compose.setContent {
            KoinApplication(configuration = koinConfiguration { modules(dependencies) }) {
                ZcashTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                        controller = rememberNavController()
                        backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                        val loadingBack = {
                            onLoadingBack()
                            check(controller.popBackStack())
                        }
                        NavHost(controller, startDestination = HomeArgs) {
                            composable<HomeArgs> { Text("Wallet home") }
                            dialogComposable<ErrorDialog> { AndroidErrorDialog(it.toRoute()) }
                            dialogComposable<ErrorBottomSheet> { AndroidErrorBottomSheet(it.toRoute()) }
                            dialogComposable<SyncErrorArgs> { SyncErrorScreen(it.toRoute()) }
                            composable<ORSwapConfirmationArgs> { ORSwapConfirmationScreen() }
                            composable<RequestArgs> {
                                RequestScreenContent(RequestState.Loading(loadingBack))
                            }
                            composable("${NavigationTargets.QR_CODE}/{addressType}") {
                                QrCodeScreenContent(QrCodeState.Loading(loadingBack))
                            }
                            composable<SignKeystoneTransactionArgs> { SignKeystoneTransactionScreen() }
                            composable<VKDetailArgs> {
                                VKDetailScreenContent(
                                    state = null, unavailable = true, isRevealed = { false }, onBack = loadingBack
                                )
                            }
                            composable<ScanKeystonePCZTRequest> {
                                KeystoneSignatureScanner(it.toRoute(), koinViewModel(), true) {
                                    controller.popBackStack()
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Wallet home").assertIsDisplayed()
    }
}
