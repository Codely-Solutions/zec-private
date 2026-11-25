package co.electriccoin.zcash.ui.screen.home.shieldfunds

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.ShieldFundsInfoProvider
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ShieldFundsUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShieldFundsInfoVMTest {
    @Test
    fun unknownBalanceHasItsOwnLabelAndOnlyEligibleBalanceEnablesShielding() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val wallet = EditAccountFixtures.zodl()
            val selected = MutableStateFlow<WalletAccount?>(wallet)
            val accounts = mockk<AccountDataSource> { every { selectedAccount } returns selected }
            val info = mockk<ShieldFundsInfoProvider> { every { observe() } returns MutableStateFlow(true) }
            val eligible = wallet.copy(transparentBalance = Zatoshi(200_000))
            val shield = mockk<ShieldFundsUseCase> {
                every { confirm(eligible, true, "shield-info") } returns {}
            }
            val vm = ShieldFundsInfoVM(GetSelectedWalletAccountUseCase(accounts), info, mockk(), shield, "shield-info")
            val collecting = backgroundScope.launch(dispatcher) { vm.state.collect {} }

            try {
                val unavailable = stringRes(R.string.shield_balance_unavailable)
                val before = assertNotNull(vm.state.value)
                assertEquals(unavailable, before.subtitle)
                assertFalse(before.primaryButton.isEnabled)

                selected.value = wallet.copy(transparentBalance = Zatoshi(0))
                val empty = assertNotNull(vm.state.value)
                assertNotEquals(unavailable, empty.subtitle)
                assertFalse(empty.primaryButton.isEnabled)

                selected.value = eligible
                assertTrue(assertNotNull(vm.state.value).primaryButton.isEnabled)
            } finally {
                collecting.cancelAndJoin()
                vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
