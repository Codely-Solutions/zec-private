package co.electriccoin.zcash.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.material3.SnackbarHostState
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasNoClickAction
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
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import co.electriccoin.zcash.ui.common.compose.LocalScreenSecurity
import co.electriccoin.zcash.ui.common.compose.ScreenSecurity
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapQuoteIfAvailableUseCase
import co.electriccoin.zcash.ui.common.usecase.RescanQrUseCase
import co.electriccoin.zcash.ui.common.usecase.SaveORSwapUseCase
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressScreen
import co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressVM
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scan.ScanZashiAddressScreen
import co.electriccoin.zcash.ui.screen.scan.ScanZashiAddressVM
import co.electriccoin.zcash.ui.screen.scan.thirdparty.ThirdPartyScan
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityHistoryArgs
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
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapRefundWarningUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarNavigationTag
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.AssetCardState
import co.electriccoin.zcash.ui.design.component.ZashiAssetCard
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
import co.electriccoin.zcash.ui.screen.redeemgift.ScanGiftCardArgs
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType
import co.electriccoin.zcash.ui.screen.request.RequestArgs
import co.electriccoin.zcash.ui.screen.request.RequestScreenContent
import co.electriccoin.zcash.ui.screen.request.model.RequestState
import co.electriccoin.zcash.ui.screen.scankeystone.KeystoneSignatureScanner
import co.electriccoin.zcash.ui.screen.scankeystone.model.ScanKeystoneState
import co.electriccoin.zcash.ui.screen.scankeystone.view.ScanKeystoneView
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystonePCZTRequest
import co.electriccoin.zcash.ui.screen.scankeystone.viewmodel.ScanKeystonePCZTViewModel
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionScreen
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionVM
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionState
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionView
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.ZashiAccountInfoListItemState
import co.electriccoin.zcash.ui.screen.swap.SwapCancelState
import co.electriccoin.zcash.ui.screen.swap.SwapCancelView
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationArgs
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationScreen
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationVM
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteArgs
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteVM
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteView
import co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressArgs
import co.electriccoin.zcash.ui.screen.swap.refundwarning.SwapRefundWarningArgs
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.provider.IsSwapRefundWarningDismissedStorageProvider
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp")
class RestoredWalletRoutesLayoutTest {
    private val contexts = mutableListOf<KoinApplication>()

    @get:Rule(order = 0)
    val closeContexts = object : ExternalResource() {
        override fun after() = contexts.forEach(KoinApplication::close)
    }

    @get:Rule(order = 1)
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
    fun invalidatedProposalLeavesTheRealRouterFreeWhileTheKeyboardRemainsOpen() {
        install()
        compose.runOnIdle {
            runTest {
                var reviewAllowed = true
                val ime = mockk<SoftwareKeyboardController> {
                    every { hide() } answers { reviewAllowed = false }
                }
                val keyboard = KeyboardManager(isOpen = true, rootSoftwareKeyboardController = ime)
                val navigator = NavigatorImpl(mockk<Activity>(), controller, keyboard, mockk(), mockk(), mockk())
                val router = NavigationRouterImpl()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    router.observePipeline().collect {
                        when (it) {
                            is CustomNavigationCommand -> navigator.executeCommand(it)
                            is NavigationCommand -> navigator.executeCommand(it)
                        }
                    }
                }
                val originalEntry = checkNotNull(controller.currentBackStackEntry).id
                val request = RequestArgs(ReceiveAddressType.Unified.ordinal)
                router.forwardFrom(originalEntry, { reviewAllowed }) { request }
                runCurrent()
                assertTrue(keyboard.isOpen)
                assertEquals(originalEntry, controller.currentBackStackEntry?.id)
                router.forward(SignKeystoneTransactionArgs)
                runCurrent()
                keyboard.onKeyboardClosed()
                runCurrent()
                assertEquals(
                    SignKeystoneTransactionArgs,
                    controller.currentBackStackEntry?.toRoute<SignKeystoneTransactionArgs>()
                )
                router.forwardFrom(originalEntry, { true }) { request }
                runCurrent()
                assertEquals(
                    SignKeystoneTransactionArgs,
                    controller.currentBackStackEntry?.toRoute<SignKeystoneTransactionArgs>()
                )

                assertTrue(controller.popBackStack())
                router.forwardFrom(originalEntry, { true }) { request }
                runCurrent()
                assertEquals(request, controller.currentBackStackEntry?.toRoute<RequestArgs>())
            }
        }
    }

    @Test
    fun migrationRecoveryUsesTheExistingHomeAfterRepeatedOpens() {
        install()
        compose.runOnIdle {
            runTest {
                val keyboard = KeyboardManager(isOpen = false, rootSoftwareKeyboardController = null)
                val navigator = NavigatorImpl(mockk<Activity>(), controller, keyboard, mockk(), mockk(), mockk())
                val home = checkNotNull(controller.currentBackStackEntry).id
                val progress = FixtureMigrationProgress("fixture-owner")
                repeat(2) {
                    controller.navigate(SignKeystoneTransactionArgs)
                    navigator.executeCommand(NavigationCommand.ReplaceAll(listOf(progress)))
                    assertEquals(progress, controller.currentBackStackEntry?.toRoute<FixtureMigrationProgress>())
                    assertTrue(controller.popBackStack())
                    assertEquals(home, controller.currentBackStackEntry?.id)
                    assertEquals(null, controller.previousBackStackEntry)
                }
            }
        }
    }

    @Test
    fun assetCardContentCannotOpenThePickerWhileDisabled() {
        val enabled = mutableStateOf(false)
        var picks = 0
        compose.setContent {
            ZcashTheme {
                ZashiAssetCard(
                    AssetCardState.Data(
                        token = stringRes("Fixture asset"), isEnabled = enabled.value, onClick = { picks++ }
                    )
                )
            }
        }
        compose.onNodeWithText("Fixture asset").assertHasNoClickAction()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("Fixture asset").performClick()
        compose.runOnIdle { assertEquals(1, picks) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun externalScannerDismissesWarningAndLetsTheOriginalFormRetry() {
        install()
        compose.runOnIdle {
            runTest {
                var resumed = true
                val router = collectOwnedNavigation { resumed }
                val preference = mockk<IsSwapRefundWarningDismissedStorageProvider>(relaxed = true)
                val warning = NavigateToSwapRefundWarningUseCase(router, preference)
                val form = checkNotNull(controller.currentBackStackEntry).id
                val first = async { warning(BigDecimal.ONE, SwapMode.EXACT_OUTPUT, form) }
                runCurrent()
                val dismissed = checkNotNull(controller.currentBackStackEntry).toRoute<SwapRefundWarningArgs>()
                controller.navigate(ScanGiftCardArgs(isFromExternalLink = true))
                runCurrent()
                assertFalse(first.await())
                warning.onContinue(dismissed, dontShowAgain = true)
                assertTrue(controller.currentBackStackEntry?.toRoute<ScanGiftCardArgs>()?.isFromExternalLink == true)
                assertTrue(controller.popBackStack())
                resumed = false
                assertFalse(warning(BigDecimal.ONE, SwapMode.EXACT_OUTPUT, form))
                assertEquals(form, controller.currentBackStackEntry?.id)

                resumed = true
                val next = async { warning(BigDecimal.ONE, SwapMode.EXACT_OUTPUT, form) }
                runCurrent()
                val retry = checkNotNull(controller.currentBackStackEntry).toRoute<SwapRefundWarningArgs>()
                warning.onContinue(retry, dontShowAgain = false)
                assertTrue(next.await())
                assertEquals(form, controller.currentBackStackEntry?.id)
                coVerify(exactly = 0) { preference.store(any(), any()) }
            }
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun genericScanOnlyReturnsDataAfterItsOwnNavigationCommits() {
        install()
        compose.runOnIdle {
            runTest {
                val router = collectOwnedNavigation()
                val scanner = NavigateToScanGenericAddressUseCase(router)
                val form = checkNotNull(controller.currentBackStackEntry).id
                val first = async { scanner.scanFrom(form) }
                runCurrent()
                val oldEntry = checkNotNull(controller.currentBackStackEntry)
                val oldRequest = oldEntry.toRoute<ScanGenericAddressArgs>()
                assertTrue(scanner.owns(oldRequest))
                controller.navigate(ScanGiftCardArgs(isFromExternalLink = true))
                assertFalse(scanner.onScanned("synthetic", null, oldRequest, sourceEntryId = oldEntry.id))
                assertNull(first.await())
                assertFalse(scanner.owns(oldRequest))
                assertEquals(ScanGiftCardArgs(true), controller.currentBackStackEntry?.toRoute<ScanGiftCardArgs>())
                assertTrue(controller.popBackStack())
                assertTrue(controller.popBackStack())

                val next = async {
                    scanner.scanFrom(form) { NavigationCommand.Replace(listOf(SignKeystoneTransactionArgs)) }
                }
                runCurrent()
                val current = checkNotNull(controller.currentBackStackEntry)
                val args = current.toRoute<ScanGenericAddressArgs>()
                assertTrue(scanner.onScanned("fixture", null, args, sourceEntryId = current.id))
                assertEquals("fixture", next.await()?.address)
                assertEquals(
                    SignKeystoneTransactionArgs,
                    controller.currentBackStackEntry?.toRoute<SignKeystoneTransactionArgs>()
                )
            }
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun launchWaitingForKeyboardRemainsPendingAfterTheActivityStops() {
        install()
        compose.runOnIdle {
            runTest {
                val keyboard = KeyboardManager(isOpen = true, rootSoftwareKeyboardController = null)
                val navigator = NavigatorImpl(mockk<Activity>(), controller, keyboard, mockk(), mockk(), mockk())
                val requests = LaunchNavigationRequests()
                val home = checkNotNull(controller.currentBackStackEntry).id
                var resumed = true
                requests.requestGiftScan()
                val first = launch { requests.deliver(true, { resumed }, navigate = navigator::executeLaunchCommand) }
                runCurrent()
                resumed = false
                keyboard.onKeyboardClosed()
                first.join()
                assertEquals(home, controller.currentBackStackEntry?.id)
                assertEquals(listOf("GIFT_SCAN"), requests.saveExternalScans())

                resumed = true
                requests.deliver(true, { resumed }, navigate = navigator::executeLaunchCommand)
                assertEquals(ScanGiftCardArgs(true), controller.currentBackStackEntry?.toRoute<ScanGiftCardArgs>())
                assertTrue(requests.saveExternalScans().isEmpty())
            }
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun migrationQueuedDuringImeCloseStillAppearsBelowTheScanner() {
        install()
        compose.runOnIdle {
            runTest {
                val keyboard = KeyboardManager(isOpen = true, rootSoftwareKeyboardController = null)
                val navigator = NavigatorImpl(mockk<Activity>(), controller, keyboard, mockk(), mockk(), mockk())
                val requests = LaunchNavigationRequests()
                val home = checkNotNull(controller.currentBackStackEntry).id
                requests.requestGiftScan()
                val first = launch { requests.deliver(true, { true }, navigate = navigator::executeLaunchCommand) }
                runCurrent()

                val notice = requests.beginMigrationProgress("owner")
                val progress = FixtureMigrationProgress("owner")
                requests.finishMigrationProgress(notice, NavigationCommand.ReplaceAll(listOf(progress)))
                keyboard.onKeyboardClosed()
                first.join()
                assertEquals(home, controller.currentBackStackEntry?.id)
                assertEquals(listOf("GIFT_SCAN"), requests.saveExternalScans())

                requests.deliver(true, { true }, navigate = navigator::executeLaunchCommand)
                assertEquals(ScanGiftCardArgs(true), controller.currentBackStackEntry?.toRoute<ScanGiftCardArgs>())
                assertEquals(progress, controller.previousBackStackEntry?.toRoute<FixtureMigrationProgress>())
                assertTrue(requests.saveExternalScans().isEmpty())
            }
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun preparedQuoteOpensWhenTheOwningActivityResumes() {
        install()
        compose.runOnIdle {
            runTest {
                val resumed = MutableStateFlow(false)
                val navigation = collectOwnedNavigation(resumed) { resumed.value }
                val publication = SwapQuoteData.Success(mockk<SwapQuote>())
                val swaps = mockk<SwapRepository> {
                    every { quote } returns MutableStateFlow(publication)
                    every { isReviewCurrent(publication) } returns true
                    every { consumeReview(publication) } returns publication
                }
                val form = checkNotNull(controller.currentBackStackEntry).id
                val openQuote = NavigateToSwapQuoteIfAvailableUseCase(swaps, navigation)
                openQuote(sourceEntryId = form) { }
                assertEquals(form, controller.currentBackStackEntry?.id)
                verify(exactly = 0) { swaps.consumeReview(any()) }

                resumed.value = true
                runCurrent()
                assertEquals(SwapQuoteArgs, controller.currentBackStackEntry?.toRoute<SwapQuoteArgs>())
                verify(exactly = 1) { swaps.consumeReview(publication) }
            }
        }
    }

    @Test
    fun thirdPartyRescanRequiresBothReadyWalletAndItsGraph() {
        install()
        compose.runOnIdle {
            runTest {
                val navigation = collectOwnedNavigation()
                val readiness = MutableStateFlow(SecretState.INCOMPLETE)
                val wallet = mockk<WalletRepository> { every { secretState } returns readiness }
                val rescan = RescanQrUseCase(wallet, navigation)
                val home = checkNotNull(controller.currentBackStackEntry).id
                val explainer = ThirdPartyScan

                controller.navigate(explainer)
                rescan(checkNotNull(controller.currentBackStackEntry).id, walletRoutesAllowed = true)
                assertEquals(home, controller.currentBackStackEntry?.id)
                readiness.value = SecretState.READY

                controller.navigate(explainer)
                rescan(checkNotNull(controller.currentBackStackEntry).id, walletRoutesAllowed = false)
                assertEquals(home, controller.currentBackStackEntry?.id)

                controller.navigate(explainer)
                rescan(checkNotNull(controller.currentBackStackEntry).id, walletRoutesAllowed = true)
                assertEquals(ScanArgs(ScanFlow.HOMEPAGE), controller.currentBackStackEntry?.toRoute<ScanArgs>())
                assertEquals(home, controller.previousBackStackEntry?.id)
            }
        }
    }

    @Test
    fun fundingAcknowledgementIsRetainedUntilTheDepositScreenCanOpenActivity() {
        install()
        compose.runOnIdle {
            runTest {
                var resumed = false
                val navigation = collectOwnedNavigation { resumed }
                val publication = SwapQuoteData.Success(mockk<SwapQuote>())
                val acknowledgement = SaveORSwapUseCase.FundingAcknowledgement(publication)
                val swaps = mockk<SwapRepository> {
                    every { clearIfCurrent(publication) } returns true
                }
                val save = SaveORSwapUseCase(swaps, mockk(), navigation)
                controller.navigate(ORSwapConfirmationArgs)
                val deposit = checkNotNull(controller.currentBackStackEntry).id
                save.finishFunding(acknowledgement, deposit)
                assertEquals(deposit, controller.currentBackStackEntry?.id)
                verify(exactly = 0) { swaps.clearIfCurrent(any()) }

                resumed = true
                save.finishFunding(acknowledgement, deposit)
                assertEquals(ActivityHistoryArgs, controller.currentBackStackEntry?.toRoute<ActivityHistoryArgs>())
                verify(exactly = 1) { swaps.clearIfCurrent(publication) }
            }
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun automaticRecoveryWaitsWhenTheScannerAlreadyOwnsTheScreen() {
        install()
        compose.runOnIdle {
            runTest {
                val keyboard = KeyboardManager(isOpen = false, rootSoftwareKeyboardController = null)
                val navigator = NavigatorImpl(mockk<Activity>(), controller, keyboard, mockk(), mockk(), mockk())
                val requests = LaunchNavigationRequests()
                backgroundScope.launch {
                    requests.deliverOnChanges(controller, true, { true }, navigator::executeLaunchCommand)
                }
                requests.requestGiftScan()
                runCurrent()
                assertEquals(ScanGiftCardArgs(true), controller.currentBackStackEntry?.toRoute<ScanGiftCardArgs>())

                val progress = FixtureMigrationProgress("owner")
                requests.requestMigrationRecovery("owner", NavigationCommand.ReplaceAll(listOf(progress)))
                runCurrent()
                assertEquals(ScanGiftCardArgs(true), controller.currentBackStackEntry?.toRoute<ScanGiftCardArgs>())

                assertTrue(controller.popBackStack())
                runCurrent()
                assertEquals(progress, controller.currentBackStackEntry?.toRoute<FixtureMigrationProgress>())
            }
        }
    }

    @Test
    fun sharedKeystoneViewsProtectTheCameraAndTheFullscreenSigningQr() {
        val security = ScreenSecurity()
        val showCamera = mutableStateOf(true)
        val showSigning = mutableStateOf(false)
        val button = ButtonState(stringRes("Fixture action"))
        compose.setContent {
            CompositionLocalProvider(LocalScreenSecurity provides security) {
                ZcashTheme {
                    if (showCamera.value) {
                        ScanKeystoneView(
                            snackbarHostState = remember { SnackbarHostState() },
                            onBack = {}, onScan = {}, onOpenSettings = {}, onScanStateChange = {},
                            validationResult = ScanValidationState.NONE,
                            state = ScanKeystoneState(stringRes("Fixture scanner"), null),
                        )
                    }
                    if (showSigning.value) {
                        SignKeystoneTransactionView(
                            SignKeystoneTransactionState(
                                onBack = {}, barTitle = stringRes("Fixture signing"),
                                title = stringRes("Scan with Keystone"), subtitle = stringRes("Fixture details"),
                                accountInfo = ZashiAccountInfoListItemState(
                                    co.electriccoin.zcash.ui.design.R.drawable.ic_item_keystone,
                                    stringRes("Keystone"), stringRes("Fixture account")
                                ),
                                badgeText = stringRes("Hardware"), qrData = "fixture-signing-qr",
                                generateNextQrCode = {}, secondaryButton = null,
                                positiveButton = button, negativeButton = button,
                            )
                        )
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(1, security.referenceCount.value)
            showCamera.value = false
            showSigning.value = true
        }
        val label = context.getString(R.string.private_keystone_open_qr)
        compose.onNodeWithContentDescription(label).performClick()
        compose.runOnIdle {
            assertEquals(1, security.referenceCount.value)
            val window = checkNotNull(ShadowDialog.getLatestDialog()?.window)
            val flag = WindowManager.LayoutParams.FLAG_SECURE
            assertEquals(flag, window.attributes.flags and flag)
            showSigning.value = false
        }
        compose.runOnIdle { assertEquals(0, security.referenceCount.value) }
    }

    @Test
    fun bothGeneralScannersAcquireScreenProtectionBeforeRenderingCameraContent() {
        val security = ScreenSecurity()
        val destination = mutableStateOf<ScanFlow?>(null)
        val dependencies = module {
            single<NavigationRouter> { router }
            viewModel {
                mockk<WalletViewModel>(relaxed = true) { every { synchronizer } returns MutableStateFlow(null) }
            }
            viewModel {
                mockk<ScanZashiAddressVM>(relaxed = true) {
                    every { state } returns MutableStateFlow(ScanValidationState.NONE)
                }
            }
            viewModel {
                mockk<ScanGenericAddressVM>(relaxed = true) { every { isOwned } returns MutableStateFlow(false) }
            }
        }
        val testContext = koinApplication { modules(dependencies) }.also(contexts::add)
        compose.setContent {
            KoinIsolatedContext(context = testContext) {
                ZcashTheme {
                    CompositionLocalProvider(LocalScreenSecurity provides security) {
                        when (destination.value) {
                            ScanFlow.HOMEPAGE -> ScanZashiAddressScreen(ScanArgs(ScanFlow.HOMEPAGE), "wallet")
                            ScanFlow.SEND -> ScanGenericAddressScreen(ScanGenericAddressArgs("generic"), "generic")
                            else -> Text("Outside scanner")
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(0, security.referenceCount.value)
            destination.value = ScanFlow.HOMEPAGE
        }
        compose.runOnIdle {
            assertEquals(1, security.referenceCount.value)
            destination.value = ScanFlow.SEND
        }
        compose.runOnIdle {
            assertEquals(1, security.referenceCount.value)
            destination.value = null
        }
        compose.runOnIdle { assertEquals(0, security.referenceCount.value) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.collectOwnedNavigation(
        resumed: Flow<Boolean> = flowOf(true),
        canCommit: () -> Boolean = { true },
    ): NavigationRouterImpl {
        val router = NavigationRouterImpl()
        val observer = router.registerEntryObserver()
        val listener = NavController.OnDestinationChangedListener { nav, _, _ ->
            observer.update(nav.currentBackStackEntry?.toActiveNavigationEntry())
        }
        controller.addOnDestinationChangedListener(listener)
        val keyboard = KeyboardManager(isOpen = false, rootSoftwareKeyboardController = null)
        val navigator = NavigatorImpl(mockk<Activity>(), controller, keyboard, mockk(), mockk(), mockk(), canCommit)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                router.observePipeline().collect {
                    when (it) {
                        is CustomNavigationCommand -> navigator.executeCommand(it)
                        is NavigationCommand -> navigator.executeCommand(it)
                    }
                }
            } finally {
                controller.removeOnDestinationChangedListener(listener)
                observer.close()
            }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            resumed.collectLatest { ready -> if (ready) router.collectPendingForwards(navigator, canCommit) }
        }
        return router
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
            viewModel { parameters ->
                ORSwapConfirmationVM(
                    swaps, cancelQuote, mockk(), router, mockk(), mockk(), AppAccessSession(), parameters.get(),
                )
            }
            viewModel {
                SignKeystoneTransactionVM(GetWalletAccountsUseCase(accounts), keys, router, mockk(), mockk(), mockk())
            }
            viewModel {
                val fresh = KeystoneProposalRepositoryImpl(accounts, mockk(), mockk(), mockk())
                ScanKeystonePCZTViewModel(mockk(), get(), fresh)
            }
        }
        val testContext = koinApplication { modules(dependencies) }.also(contexts::add)
        compose.setContent {
            KoinIsolatedContext(context = testContext) {
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
                            composable<ORSwapConfirmationArgs> { ORSwapConfirmationScreen(it.id) }
                            composable<RequestArgs> {
                                RequestScreenContent(RequestState.Loading(loadingBack))
                            }
                            composable("${NavigationTargets.QR_CODE}/{addressType}") {
                                QrCodeScreenContent(QrCodeState.Loading(loadingBack))
                            }
                            composable<SignKeystoneTransactionArgs> { SignKeystoneTransactionScreen() }
                            composable<FixtureMigrationProgress> { Text("Migration progress") }
                            composable<ScanGiftCardArgs> { Text("External scan") }
                            composable<ScanGenericAddressArgs> { Text("Generic scan") }
                            composable<ScanArgs> { Text("Wallet scanner") }
                            composable<ThirdPartyScan> { Text("Third-party scanner") }
                            composable<ActivityHistoryArgs> { Text("Activity history") }
                            dialogComposable<SwapQuoteArgs> { Text("Quote review") }
                            dialogComposable<SwapRefundWarningArgs> { Text("Refund warning") }
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

    @Serializable
    private data class FixtureMigrationProgress(val accountKeyId: String)
}
