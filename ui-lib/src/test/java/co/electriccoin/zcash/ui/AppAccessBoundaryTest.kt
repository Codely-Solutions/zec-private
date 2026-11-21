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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.window.Dialog
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.authentication.AppAccessBoundary
import co.electriccoin.zcash.ui.screen.authentication.view.AppAccessAuthentication
import co.electriccoin.zcash.ui.screen.authentication.view.WelcomeAnimationAutostart
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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
}
