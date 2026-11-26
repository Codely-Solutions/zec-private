package co.electriccoin.zcash.ui.common.provider

import io.mockk.coEvery
import io.mockk.mockk

internal fun seedRecoveryFixture(reopen: suspend () -> Boolean): WalletSeedRecovery =
    mockk<WalletSeedRecovery>().also { recovery ->
        coEvery { recovery.reopen(any(), any()) } coAnswers {
            val current = firstArg<() -> Boolean>()
            val reread = secondArg<() -> Unit>()
            val admitted = reopen()
            if (current()) {
                if (admitted) reread()
                admitted
            } else {
                null
            }
        }
    }
