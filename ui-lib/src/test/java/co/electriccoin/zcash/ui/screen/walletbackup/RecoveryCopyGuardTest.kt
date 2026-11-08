package co.electriccoin.zcash.ui.screen.walletbackup

import kotlin.test.Test
import kotlin.test.assertEquals

class RecoveryCopyGuardTest {
    @Test
    fun callbackCapturedWhileRevealedChecksCurrentHiddenState() {
        var revealed = true
        var copies = 0
        val callback = {
            copyRecoveryIfRevealed(revealed, { error("hidden secret must not be evaluated") }, { copies++ })
        }
        revealed = false
        callback()
        assertEquals(0, copies)
    }

    @Test
    fun revealedCopyPassesExactText() {
        var copied = ""
        copyRecoveryIfRevealed(true, { "fake phrase only" }, { copied = it })
        assertEquals("fake phrase only", copied)
        copyRecoveryIfRevealed(true, { "1234567" }, { copied = it })
        assertEquals("1234567", copied)
    }
}
