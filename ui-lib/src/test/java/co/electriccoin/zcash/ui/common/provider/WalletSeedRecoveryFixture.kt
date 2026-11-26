package co.electriccoin.zcash.ui.common.provider

import io.mockk.coEvery
import io.mockk.mockk

internal fun seedRecoveryFixture(erase: suspend () -> Boolean): WalletSeedRecovery =
    mockk<WalletSeedRecovery>().apply { eraseResult(erase) }

internal fun WalletSeedRecovery.eraseResult(erase: suspend () -> Boolean) {
    coEvery { eraseAndRestore(any(), any(), any()) } coAnswers {
        secondArg<() -> Unit>().invoke()
        val erased = erase()
        if (erased) thirdArg<suspend () -> Unit>().invoke()
        erased
    }
}
