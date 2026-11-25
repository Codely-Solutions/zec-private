package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentAccess
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

internal class LedgerEnrollmentFixture(val sdk: Synchronizer = mockk(relaxed = true)) {
    val wallet = WalletWriteAccess()
    val access = WalletEnrollmentAccess(wallet, mockk<WalletCoordinator> {
        every { synchronizer } returns MutableStateFlow<Synchronizer?>(sdk)
    })
    val owner = checkNotNull(access.capture())

    suspend fun replaceWallet() {
        wallet.beginReset()
        wallet.finishReset()
        wallet.replaceWallet {}
    }
}
