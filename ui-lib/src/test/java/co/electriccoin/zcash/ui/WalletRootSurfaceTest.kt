package co.electriccoin.zcash.ui

import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import kotlin.test.Test
import kotlin.test.assertEquals

class WalletRootSurfaceTest {
    @Test
    fun retryWithholdsTheNavigationGraphUntilAReadResult() {
        val reads = listOf(
            SecretState.READY,
            SecretState.UNAVAILABLE,
            SecretState.LOADING,
            SecretState.UNAVAILABLE,
            SecretState.LOADING,
            SecretState.READY,
        )
        assertEquals(
            listOf(
                WalletRootSurface.NAVIGATION,
                WalletRootSurface.RECOVERY,
                WalletRootSurface.WAITING,
                WalletRootSurface.RECOVERY,
                WalletRootSurface.WAITING,
                WalletRootSurface.NAVIGATION,
            ),
            reads.map { walletRootSurface(it, isResetPending = false) },
        )
    }

    @Test
    fun coldStartupWaitsAndPendingResetTakesPriority() {
        assertEquals(WalletRootSurface.WAITING, walletRootSurface(SecretState.LOADING, isResetPending = false))
        assertEquals(WalletRootSurface.NAVIGATION, walletRootSurface(SecretState.NONE, isResetPending = false))
        assertEquals(WalletRootSurface.NAVIGATION, walletRootSurface(SecretState.INCOMPLETE, isResetPending = false))
        for (secretState in SecretState.entries) {
            assertEquals(WalletRootSurface.RESET, walletRootSurface(secretState, isResetPending = true))
        }
    }
}
