package co.electriccoin.zcash.ui.screen.restore.height

import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.ledger.LedgerAccountImporter
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.ConfirmResyncUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateHWWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateKeystoneAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetResyncDataFromHeightUseCase
import co.electriccoin.zcash.ui.design.R as DesignR
import co.electriccoin.zcash.ui.screen.heightinfo.HeightInfoArgs
import co.electriccoin.zcash.ui.screen.resync.date.ResyncDateArgs
import co.electriccoin.zcash.ui.screen.resync.date.ResyncDateVM
import co.electriccoin.zcash.ui.screen.resync.estimation.ResyncEstimationArgs
import co.electriccoin.zcash.ui.screen.resync.estimation.ResyncEstimationVM
import java.time.YearMonth
import co.electriccoin.zcash.ui.common.usecase.ResyncErrorMapperUseCase
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyAccountImporter
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyContent
import co.electriccoin.zcash.ui.design.component.InnerTextFieldState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextFieldParser
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.BlockHeightState
import co.electriccoin.zcash.ui.screen.common.birthdayHeightOrNull
import co.electriccoin.zcash.ui.screen.connecthw.HWWalletBranding
import co.electriccoin.zcash.ui.screen.connecthw.HWWalletEnrollment
import co.electriccoin.zcash.ui.screen.connecthw.KeystoneEnrollmentSession
import co.electriccoin.zcash.ui.screen.connecthw.height.HWHeightArgs
import co.electriccoin.zcash.ui.screen.connecthw.height.HWHeightVM
import co.electriccoin.zcash.ui.screen.resync.height.ResyncHeightVM
import co.electriccoin.zcash.ui.screen.restore.tor.RestoreTorArgs
import com.keystone.module.ZcashAccounts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreHeightBoundaryReviewTest {
    private val minimum = VersionInfo.NETWORK.saplingActivationHeight.value
    private val maximum = 4_294_967_295L
    private val target = minimum + 10_000
    private val wrapped = "18446744073709551616".toBigDecimal().add(target.toBigDecimal()).toPlainString()
    private val invalid = listOf("$target.5", wrapped, "-1", "${minimum - 1}", "${maximum + 1}", "")
    private val valid = listOf("$minimum" to minimum, "$maximum" to maximum)
    private val router = mockk<NavigationRouter>(relaxed = true)
    private val errorMapper = ErrorMapperUseCase(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun parserRequiresAnExactIntegerInTheSupportedBirthdayRange() {
        assertEquals(wrapped.toBigDecimal(), heightText(wrapped).amount)
        assertEquals("$target.5".toBigDecimal(), heightText("$target.5").amount)
        for (input in invalid) {
            assertNull(heightText(input).birthdayHeightOrNull(), input)
        }
        assertNull(NumberTextFieldInnerState.fromAmount((-1).toBigDecimal()).birthdayHeightOrNull())
        for ((input, expected) in valid + ("$target.000" to target)) {
            assertEquals(BlockHeight.new(expected), heightText(input).birthdayHeightOrNull(), input)
        }
    }

    @Test
    fun restoreRejectsInvalidCurrentInputFromFreshAndRetainedCallbacks() = runTest {
        val vm = RestoreHeightVM(RestoreHeight(sessionId = "review-session"), router)
        observe(vm, vm.state) {
            rejectInvalidDrafts { vm.state.value }
            verify(exactly = 0) { router.forward(any()) }
            for ((input, expected) in valid) {
                submitHeight(input) { vm.state.value }
                verify(exactly = 1) {
                    router.forward(RestoreTorArgs(sessionId = "review-session", blockHeight = expected))
                }
            }
        }
    }

    @Test
    fun resyncRejectsInvalidCurrentInputBeforeRewindingTheWallet() = runTest {
        val synchronizer = mockk<Synchronizer> {
            coEvery { rewindToNearestHeight(any()) } coAnswers { firstArg() }
        }
        val provider = mockk<SynchronizerProvider>(relaxed = true) {
            coEvery { getSynchronizer() } returns synchronizer
        }
        val vm = ResyncHeightVM(
            router,
            ConfirmResyncUseCase(provider, mockk(relaxed = true), mockk(relaxed = true), router),
            ResyncErrorMapperUseCase(errorMapper),
        )
        observe(vm, vm.state) {
            rejectInvalidDrafts { assertNotNull(vm.state.value.content) }
            assertNull(vm.state.value.error)
            coVerify(exactly = 0) { provider.getSynchronizer() }
            for ((input, expected) in valid) {
                submitHeight(input) { assertNotNull(vm.state.value.content) }
                coVerify(exactly = 1) { synchronizer.rewindToNearestHeight(BlockHeight.new(expected)) }
            }
        }
    }

    @Test
    fun resyncBirthdayStepsNameAndOpenTheSameHelpAction() = runTest {
        val resyncDate = mockk<GetResyncDataFromHeightUseCase> {
            coEvery { this@mockk(any<BlockHeight>()) } returns YearMonth.of(2020, 4)
        }
        val errors = ResyncErrorMapperUseCase(errorMapper)
        val date = ResyncDateVM(ResyncDateArgs("resync", target), router, mockk<Application>(), resyncDate, errors)
        val estimate = ResyncEstimationVM(
            ResyncEstimationArgs("resync", target), router, mockk(), resyncDate, mockk(), errors,
        )
        val height = ResyncHeightVM(router, mockk(), errors)
        val name = stringRes(DesignR.string.accessibility_wallet_birthday_help)
        observe(date, date.state) {
            val button = assertNotNull(date.state.value.content?.dialogButton)
            assertEquals(name, button.contentDescription)
            button.onClick()
        }
        observe(estimate, estimate.state) {
            val button = assertNotNull(estimate.state.value.content).dialogButton
            assertEquals(name, button.contentDescription)
            button.onClick()
        }
        observe(height, height.state) {
            val button = assertNotNull(height.state.value.content?.dialogButton)
            assertEquals(name, button.contentDescription)
            button.onClick()
        }
        verify(exactly = 3) { router.forward(HeightInfoArgs) }
    }

    @Test
    fun sharedEnrollmentRejectsInvalidCurrentInputForEveryEnrollment() = runTest {
        for ((input, expected) in valid) {
            val io = EnrollmentIo(router)
            for (enrollment in io.enrollments) {
                val vm = HWHeightVM(HWHeightArgs(enrollment), io.createAccount, router, errorMapper, viewOnlyContent())
                observe(vm, vm.state) {
                    rejectInvalidDrafts { assertNotNull(vm.state.value.content) }
                    assertNull(vm.state.value.error)
                    io.verifyCalls(enrollment, BlockHeight.new(expected), count = 0)
                    submitHeight(input) { assertNotNull(vm.state.value.content) }
                    io.verifyCalls(enrollment, BlockHeight.new(expected), count = 1)
                }
            }
        }
    }

    private fun TestScope.rejectInvalidDrafts(state: () -> BlockHeightState) {
        for (input in invalid) {
            state().blockHeight.onValueChange(heightText(target.toString()))
            runCurrent()
            val retained = state().primaryButton
            assertTrue(retained.isEnabled)
            state().blockHeight.onValueChange(heightText(input))
            retained.onClick()
            runCurrent()
            assertFalse(state().primaryButton.isEnabled, input)
            retained.onClick()
            state().primaryButton.onClick()
            runCurrent()
        }
    }

    private fun TestScope.submitHeight(input: String, state: () -> BlockHeightState) {
        state().blockHeight.onValueChange(heightText(input))
        runCurrent()
        assertTrue(state().primaryButton.isEnabled, input)
        state().primaryButton.onClick()
        runCurrent()
    }

    private fun TestScope.observe(vm: ViewModel, flow: Flow<*>, checkState: () -> Unit) {
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
        try {
            runCurrent()
            checkState()
        } finally {
            subscription.cancel()
            vm.viewModelScope.cancel()
        }
    }

    private fun heightText(input: String): NumberTextFieldInnerState {
        val normalized = ZashiNumberTextFieldParser.normalizeInput(input)
        return NumberTextFieldInnerState(
            innerTextFieldState = InnerTextFieldState(value = stringRes(normalized)),
            amount = ZashiNumberTextFieldParser.toBigDecimalOrNull(normalized),
        )
    }

    private fun viewOnlyContent(): ViewOnlyContent = mockk {
        every { enrollmentBranding } returns HWWalletBranding(
            logo = null,
            newOrActive = null,
            enterBlockHeightManually = stringRes("Enter block height"),
            connect = stringRes("Continue"),
            enterManuallyTestTag = "VIEW_ONLY_ENTER_MANUALLY",
            connectTestTag = "VIEW_ONLY_CONTINUE",
            blockHeightFieldTestTag = "VIEW_ONLY_HEIGHT_FIELD",
            dateTitle = stringRes("First Account Transaction"),
            dateSubtitle = stringRes("Choose the approximate date of this account's first transaction."),
        )
    }

    private class EnrollmentIo(router: NavigationRouter) {
        private val keystoneAccounts = mockk<ZcashAccounts>(relaxed = true) {
            every { accounts } returns listOf(mockk(relaxed = true))
        }
        private val walletEnrollment = EditAccountFixtures.enrollmentAccess()
        private val session = KeystoneEnrollmentSession(walletEnrollment)
        private val account = mockk<Account>()
        private val accounts = mockk<AccountDataSource>(relaxed = true) {
            coEvery { importKeystoneAccount(any(), any(), any(), any(), any()) } returns account
        }
        private val ledger = mockk<LedgerAccountImporter>(relaxed = true) {
            every { hasPendingPairing() } returns true
            every { committedBirthday() } returns null
            every { committedBirthdays } returns flowOf(null)
        }
        private val viewOnly = mockk<ViewOnlyAccountImporter>(relaxed = true) {
            every { hasPendingImport() } returns true
            every { committedBirthday() } returns null
            every { committedBirthdays } returns flowOf(null)
        }

        val enrollments = listOf(
            HWWalletEnrollment.Keystone(EditAccountFixtures.startKeystone(session, keystoneAccounts)),
            HWWalletEnrollment.Ledger,
            HWWalletEnrollment.ViewOnly,
        )
        val createAccount = CreateHWWalletAccountUseCase(
            keystoneSession = session,
            createKeystoneAccount = CreateKeystoneAccountUseCase(accounts, mockk(relaxed = true), walletEnrollment),
            ledgerAccountImporter = ledger,
            viewOnlyAccountImporter = viewOnly,
            navigationRouter = router,
        )

        fun verifyCalls(enrollment: HWWalletEnrollment, height: BlockHeight, count: Int) {
            when (enrollment) {
                is HWWalletEnrollment.Keystone -> {
                    coVerify(exactly = count) { accounts.importKeystoneAccount(any(), any(), any(), any(), any()) }
                    coVerify(exactly = count) { accounts.importKeystoneAccount(any(), any(), any(), any(), height) }
                }
                HWWalletEnrollment.Ledger -> {
                    coVerify(exactly = count) { ledger.importAccount(any()) }
                    coVerify(exactly = count) { ledger.importAccount(height) }
                }
                HWWalletEnrollment.ViewOnly -> {
                    verify(exactly = count) { viewOnly.continueWithBirthday(any()) }
                    verify(exactly = count) { viewOnly.continueWithBirthday(height) }
                }
            }
        }
    }
}
