package co.electriccoin.zcash.ui.screen.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import co.electriccoin.zcash.ui.common.repository.WalletInitializationState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.onboarding.view.Onboarding
import java.util.concurrent.atomic.AtomicInteger

class OnboardingTestSetup(
    private val composeTestRule: ComposeContentTestRule,
) {
    private val onCreateWalletCallbackCount = AtomicInteger(0)
    private val onImportWalletCallbackCount = AtomicInteger(0)
    private val onContinueWalletCallbackCount = AtomicInteger(0)

    fun getOnCreateWalletCallbackCount(): Int {
        composeTestRule.waitForIdle()
        return onCreateWalletCallbackCount.get()
    }

    fun getOnImportWalletCallbackCount(): Int {
        composeTestRule.waitForIdle()
        return onImportWalletCallbackCount.get()
    }

    fun getOnContinueWalletCallbackCount(): Int {
        composeTestRule.waitForIdle()
        return onContinueWalletCallbackCount.get()
    }

    @Composable
    @Suppress("TestFunctionName")
    fun DefaultContent(initialization: WalletInitializationState) {
        ZcashTheme {
            Onboarding(
                initialization = initialization,
                onImportWallet = { onImportWalletCallbackCount.incrementAndGet() },
                onCreateWallet = { onCreateWalletCallbackCount.incrementAndGet() },
                onContinueWallet = { onContinueWalletCallbackCount.incrementAndGet() },
                onRetrySetup = {},
            )
        }
    }

    fun setDefaultContent(initialization: WalletInitializationState = WalletInitializationState.IDLE) {
        composeTestRule.setContent {
            DefaultContent(initialization)
        }
    }
}
