package co.electriccoin.zcash.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import androidx.navigation.toRoute
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.usecase.GetPersistableWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletBackupData
import co.electriccoin.zcash.ui.common.usecase.WalletBackupMessageUseCase
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.authentication.AppAccessBoundary
import co.electriccoin.zcash.ui.screen.authentication.view.AppAccessAuthentication
import co.electriccoin.zcash.ui.screen.authentication.view.WelcomeAnimationAutostart
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.walletbackup.AndroidWalletBackup
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackup
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.koin.compose.KoinApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppAccessBoundaryTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun canceledAuthenticationExposesNoWalletAction() {
        val session = AppAccessSession()
        var callbackCount = 0
        compose.setContent {
            ZcashTheme {
                BlankSurface(Modifier.fillMaxSize()) {
                    AppAccessBoundary(
                        session = session,
                        lockedContent = {
                            WelcomeAnimationAutostart(showAuthLogo = true, onRetry = {})
                            AppAccessAuthentication(
                                onRetry = {},
                                showAuthLogo = true,
                                welcomeAnimVisibility = true,
                            )
                        }
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Button(
                                onClick = { callbackCount++ },
                                modifier = Modifier.align(Alignment.BottomCenter).testTag("wallet-action")
                            ) { Text("Wallet action") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("wallet-action", useUnmergedTree = true).assertDoesNotExist()
        compose.onRoot().performTouchInput { click(bottomCenter) }
        compose.runOnIdle { assertEquals(0, callbackCount) }
        compose.runOnIdle { session.unlock() }
        compose.onNodeWithTag("wallet-action").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, callbackCount) }
    }

    @Test
    fun retryRemainsDrawnAndActionableAfterTheWelcomeAnimation() {
        val session = AppAccessSession().apply { unlock() }
        val context = RuntimeEnvironment.getApplication()
        val retryDescription =
            context.getString(
                R.string.authentication_failed_welcome_icon_cont_desc,
                context.getString(R.string.app_name)
            )
        var retryCount = 0
        compose.setContent {
            ZcashTheme {
                BlankSurface(Modifier.fillMaxSize()) {
                    AppAccessBoundary(
                        session = session,
                        lockedContent = {
                            AppAccessAuthentication(
                                onRetry = { retryCount++ },
                                showAuthLogo = true,
                                welcomeAnimVisibility = false,
                            )
                        }
                    ) {
                        Button(onClick = {}, modifier = Modifier.testTag("wallet-action")) { Text("Wallet action") }
                    }
                }
            }
        }
        compose.onNodeWithTag("wallet-action").assertExists()
        compose.runOnIdle { session.lock() }
        compose.onNodeWithTag("wallet-action", useUnmergedTree = true).assertDoesNotExist()

        val retry = compose.onNodeWithContentDescription(retryDescription)
        val pixels = retry.captureToImage().toPixelMap()
        val background = pixels[0, 0]
        val hasGlyph = (0 until pixels.height).any { y ->
            (0 until pixels.width).any { x -> pixels[x, y] != background }
        }
        assertTrue(hasGlyph, "The retry control must draw its key glyph")
        retry.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, retryCount) }
        compose.onNodeWithTag("wallet-action", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun relockUnmountsWalletDialogAndRevokesItsCallbackGrant() {
        val session = AppAccessSession().apply { unlock() }
        lateinit var walletAction: () -> Unit
        var callbackCount = 0
        compose.setContent {
            ZcashTheme {
                AppAccessBoundary(session, lockedContent = { Text("Locked") }) { grant ->
                    walletAction = {
                        if (session.permits(grant)) callbackCount++
                    }
                    Dialog(onDismissRequest = {}) {
                        Button(onClick = walletAction, modifier = Modifier.testTag("wallet-dialog-action")) {
                            Text("Wallet dialog action")
                        }
                    }
                }
            }
        }

        compose.onNodeWithTag("wallet-dialog-action").performTouchInput { click() }
        val staleAction = walletAction
        compose.runOnIdle {
            session.lock()
            staleAction()
        }
        compose.onNodeWithTag("wallet-dialog-action", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { session.unlock() }
        compose.runOnIdle { staleAction() }
        compose.onNodeWithTag("wallet-dialog-action").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, callbackCount) }
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp")
    fun relockKeepsTheRecoveryDestinationButRequiresANewReveal() {
        val session = AppAccessSession().apply { unlock() }
        val fixture = recoveryFixture()
        val biometricResponses = List(2) { CompletableDeferred<Unit>() }
        var biometricRequests = 0
        val biometrics = mockk<BiometricRepository> {
            coEvery { requestBiometrics(any()) } coAnswers { biometricResponses[biometricRequests++].await() }
        }
        val wallet = GetPersistableWalletUseCase(mockk<PersistableWalletProvider> {
            every { persistableWallet } returns flowOf(fixture)
        })
        var viewModelCreations = 0
        val testModule = module {
            viewModel { parameters ->
                viewModelCreations++
                WalletBackupViewModel(
                    walletBackupMessageUseCase = mockk<WalletBackupMessageUseCase> {
                        every { observe() } returns flowOf(WalletBackupData.Unavailable)
                    },
                    getPersistableWallet = wallet,
                    args = parameters.get(),
                    navigationRouter = mockk(relaxed = true),
                    onUserSavedWalletBackup = mockk(relaxed = true),
                    remindWalletBackupLater = mockk(relaxed = true),
                    biometricRepository = biometrics,
                    copyRecoveryToClipboard = mockk(relaxed = true),
                    appAccessSession = session
                )
            }
        }
        val context = RuntimeEnvironment.getApplication()
        val revealLabel = context.getString(R.string.recoveryPhraseDisplay_reveal)
        val copyLabel = context.getString(R.string.recovery_copy_phrase)
        lateinit var navController: NavHostController
        compose.setContent {
            KoinApplication(application = { modules(testModule) }) {
                ZcashTheme {
                    navController = rememberNavController()
                    AppAccessBoundary(session, lockedContent = { Text("Locked") }) {
                        NavHost(navController, startDestination = MainAppGraph) {
                            navigation<MainAppGraph>(startDestination = HomeArgs) {
                                composable<HomeArgs> { Text("Wallet home", Modifier.testTag("wallet-home")) }
                                composable<WalletBackup> { AndroidWalletBackup(it.toRoute()) }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("wallet-home").assertExists()
        compose.runOnIdle { navController.navigate(WalletBackup(false)) }
        val reveal = compose.onNodeWithText(revealLabel)
        val copy = compose.onNodeWithText(copyLabel)
        reveal.performScrollTo().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, biometricRequests)
            biometricResponses[0].complete(Unit)
        }
        copy.assertExists()

        compose.runOnIdle { session.lock() }
        reveal.assertDoesNotExist()
        copy.assertDoesNotExist()
        compose.onNodeWithTag("wallet-home").assertDoesNotExist()

        compose.runOnIdle { session.unlock() }
        reveal.assertExists()
        copy.assertDoesNotExist()
        reveal.performScrollTo().performTouchInput { click() }
        copy.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, viewModelCreations)
            assertEquals(2, biometricRequests)
            biometricResponses[1].complete(Unit)
        }
        copy.assertExists()
    }

    private fun recoveryFixture() = PersistableWallet(
        network = ZcashNetwork.Testnet,
        birthday = WalletFixture.Alice.getBirthday(ZcashNetwork.Testnet),
        endpoint = LightWalletEndpoint("unused.invalid", 443, true),
        seedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase),
        walletInitMode = WalletInitMode.ExistingWallet
    )
}
