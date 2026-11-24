package co.electriccoin.zcash.ui.screen.error

import co.electriccoin.zcash.ui.common.usecase.WalletReportAccess
import io.mockk.every
import io.mockk.mockk

internal fun noReportAccess() = mockk<WalletReportAccess> {
    every { bind(any()) } returns { { false } }
}
