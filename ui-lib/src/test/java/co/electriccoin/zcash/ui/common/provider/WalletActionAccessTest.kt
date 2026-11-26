package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ConfirmedAccountSelection
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.WalletAccount
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletActionAccessTest {
    @Test
    fun signingApprovalRemainsTiedToItsOriginalWalletAccountEngineAndAppGrant() = runTest {
        val appAccess = AppAccessSession()
        val walletAccess = WalletWriteAccess()
        val account = mockk<WalletAccount>()
        val accounts = mockk<AccountDataSource>()
        var sameSelection = true
        var sameEngine = true
        coEvery { accounts.confirmSelection(account) } returns
            ConfirmedAccountSelection({ sameSelection }, { sameEngine })
        val access = WalletActionAccess(appAccess, walletAccess, accounts)

        assertNull(access.capture(account))
        appAccess.unlock()
        val first = assertNotNull(access.capture(account))
        assertTrue(first())

        sameEngine = false
        assertFalse(first())
        assertNull(access.capture(account))
        sameEngine = true
        sameSelection = false
        assertFalse(first())
        sameSelection = true
        appAccess.lock()
        appAccess.unlock()
        assertFalse(first())

        val second = assertNotNull(access.capture(account))
        walletAccess.replaceWallet {}
        assertFalse(second())
    }
}
