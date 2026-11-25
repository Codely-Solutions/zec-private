package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.GiftCardRedeemer
import cash.z.ecc.android.sdk.GiftCardRedeemers
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.ADDRESS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.LINK
import co.electriccoin.zcash.ui.common.model.GiftCardException
import co.electriccoin.zcash.ui.common.model.GiftCardStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GiftCardTransportChangeTest : GiftCardDataSourceTestFixture() {
    @Test
    fun heldGiftChecksTheNewWalletServerAndCannotSubmitOnTheOldRoute() = runTest {
        val dataSource = dataSource(this)
        val handle = dataSource.parse(LINK).summary.handle
        val backup = LightWalletEndpoint(host = "replacement.example", port = 443, isSecure = true)
        coEvery { persistableWalletProvider.getPersistableWallet() } returns wallet(ZcashNetwork.Mainnet, backup)
        coEvery { replacementRedeemer.check() } returns GiftCardRedeemer.Status.Empty

        assertFailsWith<GiftCardException.NotChecked> {
            dataSource.redeem(handle, ADDRESS) { error("A stale server reached SDK admission") }
        }
        assertEquals(GiftCardStatus.Empty, dataSource.check(handle))

        coVerify(exactly = 0) { redeemer.redeem(any(), any(), any()) }
        coVerify(exactly = 0) { redeemer.check() }
        coVerify(exactly = 1) { replacementRedeemer.check() }
        verify(exactly = 1) {
            GiftCardRedeemers.new(any(), any(), ZcashNetwork.Mainnet, backup, isTorEnabled = true, alias = any())
        }
    }

    @Test
    fun torModeChangeWaitsUntilAnyHeldGiftRouteIsClosed() = runTest {
        val dataSource = dataSource(this)
        val handle = dataSource.parse(LINK).summary.handle
        assertFalse(dataSource.canChangeTorMode())

        dataSource.close(handle)
        assertTrue(dataSource.canChangeTorMode())
        coVerify(exactly = 1) { redeemer.close() }
    }
}
