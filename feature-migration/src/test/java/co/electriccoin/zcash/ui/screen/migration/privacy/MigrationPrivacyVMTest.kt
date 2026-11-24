package co.electriccoin.zcash.ui.screen.migration.privacy

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryArgs
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import co.electriccoin.zcash.ui.design.R as DesignR

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationPrivacyVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun confirmCarriesOptOutOnlyInTheCurrentAccountDraft() =
        runTest {
            val router = mockk<NavigationRouter>(relaxed = true)
            val vm = vm(router = router)
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            // Default is true; toggle off, then confirm.
            vm.state.value
                ?.checkbox
                ?.onClick
                ?.invoke()
            advanceUntilIdle()
            vm.state.value
                ?.onConfirm
                ?.invoke()
            advanceUntilIdle()

            verify(exactly = 1) { router.forward(MigrationBatteryArgs(owner, useTor = false)) }
            collectJob.cancel()
        }

    @Test
    fun confirmCarriesThePrivacyPreferredDefault() =
        runTest {
            val router = mockk<NavigationRouter>(relaxed = true)
            val vm = vm(router = router)
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.state.value
                ?.onConfirm
                ?.invoke()
            advanceUntilIdle()

            verify(exactly = 1) { router.forward(MigrationBatteryArgs(owner, useTor = true)) }
            collectJob.cancel()
        }

    @Test
    fun checkboxDefaultsToCheckedRegardlessOfProviderState() =
        runTest {
            val vm = vm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(
                true,
                vm.state.value
                    ?.checkbox
                    ?.isChecked
            )
            collectJob.cancel()
        }

    @Test
    fun immediateDirectReviewDisclosesWalletTorAndLeavesMigrationPreferenceAlone() = runTest {
        val router = mockk<NavigationRouter>(relaxed = true)
        val vm = vm(mode = MigrationMode.IMMEDIATE, router = router)
        val observer = launch { vm.state.collect {} }
        advanceUntilIdle()
        val state = assertNotNull(vm.state.value)

        assertNull(state.checkbox)
        assertEquals(stringRes(DesignR.string.migration_common_continueWithoutTor), state.confirmLabel)
        state.onConfirm()
        advanceUntilIdle()

        verify(exactly = 1) { router.forward(MigrationReviewArgs(MigrationMode.IMMEDIATE, owner)) }
        observer.cancel()
    }

    private val owner = "reviewed-account"

    private fun vm(
        mode: MigrationMode = MigrationMode.AUTOMATIC,
        router: NavigationRouter = mockk(relaxed = true),
    ) = MigrationPrivacyVM(
        args = MigrationPrivacyArgs(mode, owner),
        navigationRouter = router,
    )
}
