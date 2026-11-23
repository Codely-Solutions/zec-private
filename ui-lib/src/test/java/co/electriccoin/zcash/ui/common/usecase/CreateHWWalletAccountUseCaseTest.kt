package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.ledger.LedgerAccountImporter
import co.electriccoin.zcash.ui.common.model.LedgerPairingMissingException
import co.electriccoin.zcash.ui.common.model.ViewOnlyPendingImportMissingException
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyAccountImporter
import co.electriccoin.zcash.ui.screen.connecthw.HWWalletEnrollment
import co.electriccoin.zcash.ui.screen.connecthw.KeystoneEnrollmentSession
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenArgs
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenFlow
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystoneSignInRequest
import com.keystone.module.ZcashAccount
import com.keystone.module.ZcashAccounts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The single vendor branch of the shared enrollment flow: a Keystone enrollment resolves its session and
 * goes through the Keystone import, a Ledger enrollment goes straight to the Ledger one and returns
 * to the root when its pairing went missing, a view-only enrollment hands its birthday to the
 * view-only importer, which goes on to name the account, and returns to the root when its pending
 * key went missing, and none is ready when what it needs is
 * gone.
 */
class CreateHWWalletAccountUseCaseTest {
    private val birthday = BlockHeight.new(2_500_000L)

    @Test
    fun aKeystoneEnrollmentImportsThroughTheKeystoneUseCase() =
        runTest {
            val account = mockk<ZcashAccount>()
            val accounts = mockk<ZcashAccounts> { every { this@mockk.accounts } returns listOf(account) }
            val session = KeystoneEnrollmentSession()
            val enrollment = HWWalletEnrollment.Keystone(session.start(accounts))
            val createKeystoneAccount = mockk<CreateKeystoneAccountUseCase>(relaxed = true)
            val ledgerAccountImporter = mockk<LedgerAccountImporter>(relaxed = true)
            val navigationRouter = mockk<NavigationRouter>(relaxed = true)
            val useCase =
                useCase(
                    keystoneSession = session,
                    createKeystoneAccount = createKeystoneAccount,
                    ledgerAccountImporter = ledgerAccountImporter,
                    navigationRouter = navigationRouter,
                )

            useCase(enrollment, birthday)

            coVerify(exactly = 1) { createKeystoneAccount.invoke(accounts, account, birthday) }
            coVerify(exactly = 0) { ledgerAccountImporter.importAccount(any()) }
            assertFalse(useCase.isReady(enrollment))
            verify(exactly = 1) {
                navigationRouter.replaceFrom(ScanKeystoneSignInRequest::class, KeepOpenArgs(KeepOpenFlow.KEYSTONE))
            }
        }

    @Test
    fun aLedgerEnrollmentImportsThroughTheLedgerUseCase() =
        runTest {
            val createKeystoneAccount = mockk<CreateKeystoneAccountUseCase>(relaxed = true)
            val ledgerAccountImporter = mockk<LedgerAccountImporter>(relaxed = true)
            val useCase =
                useCase(
                    createKeystoneAccount = createKeystoneAccount,
                    ledgerAccountImporter = ledgerAccountImporter,
                )

            useCase(HWWalletEnrollment.Ledger, birthday)

            coVerify(exactly = 1) { ledgerAccountImporter.importAccount(birthday) }
            coVerify(exactly = 0) { createKeystoneAccount.invoke(any(), any(), any()) }
        }

    @Test
    fun aLedgerPairingLostBeforeTheImportReturnsToTheRootInsteadOfFailing() =
        runTest {
            val ledgerAccountImporter =
                mockk<LedgerAccountImporter>(relaxed = true) {
                    coEvery { importAccount(any()) } throws LedgerPairingMissingException()
                }
            val navigationRouter = mockk<NavigationRouter>(relaxed = true)
            val useCase = useCase(ledgerAccountImporter = ledgerAccountImporter, navigationRouter = navigationRouter)

            useCase(HWWalletEnrollment.Ledger, birthday)

            verify(exactly = 1) { navigationRouter.backToRoot() }
        }

    @Test
    fun aSuccessfulLedgerImportStaysOnTheFlow() =
        runTest {
            val navigationRouter = mockk<NavigationRouter>(relaxed = true)

            useCase(navigationRouter = navigationRouter)(HWWalletEnrollment.Ledger, birthday)

            verify(exactly = 0) { navigationRouter.backToRoot() }
        }

    @Test
    fun aViewOnlyEnrollmentHandsItsBirthdayToTheViewOnlyImporter() =
        runTest {
            val createKeystoneAccount = mockk<CreateKeystoneAccountUseCase>(relaxed = true)
            val ledgerAccountImporter = mockk<LedgerAccountImporter>(relaxed = true)
            val viewOnlyAccountImporter = mockk<ViewOnlyAccountImporter>(relaxed = true)
            val navigationRouter = mockk<NavigationRouter>(relaxed = true)
            val useCase =
                useCase(
                    createKeystoneAccount = createKeystoneAccount,
                    ledgerAccountImporter = ledgerAccountImporter,
                    viewOnlyAccountImporter = viewOnlyAccountImporter,
                    navigationRouter = navigationRouter,
                )

            useCase(HWWalletEnrollment.ViewOnly, birthday)

            verify(exactly = 1) { viewOnlyAccountImporter.continueWithBirthday(birthday) }
            coVerify(exactly = 0) { ledgerAccountImporter.importAccount(any()) }
            coVerify(exactly = 0) { createKeystoneAccount.invoke(any(), any(), any()) }
            verify(exactly = 0) { navigationRouter.backToRoot() }
        }

    @Test
    fun aViewOnlyKeyLostBeforeTheImportReturnsToTheRootInsteadOfFailing() =
        runTest {
            val viewOnlyAccountImporter =
                mockk<ViewOnlyAccountImporter>(relaxed = true) {
                    every { continueWithBirthday(any()) } throws ViewOnlyPendingImportMissingException()
                }
            val navigationRouter = mockk<NavigationRouter>(relaxed = true)
            val useCase =
                useCase(viewOnlyAccountImporter = viewOnlyAccountImporter, navigationRouter = navigationRouter)

            useCase(HWWalletEnrollment.ViewOnly, birthday = null)

            verify(exactly = 1) { navigationRouter.backToRoot() }
        }

    @Test
    fun aLostKeystoneSessionRestartsScanningWithoutImporting() =
        runTest {
            val createKeystoneAccount = mockk<CreateKeystoneAccountUseCase>(relaxed = true)
            val navigationRouter = mockk<NavigationRouter>(relaxed = true)
            val useCase =
                useCase(createKeystoneAccount = createKeystoneAccount, navigationRouter = navigationRouter)

            useCase(HWWalletEnrollment.Keystone("expired"), birthday = null)

            coVerify(exactly = 0) { createKeystoneAccount.invoke(any(), any(), any()) }
            verify(exactly = 1) {
                navigationRouter.replaceFrom(ScanKeystoneSignInRequest::class, ScanKeystoneSignInRequest)
            }
        }

    @Test
    fun readinessFollowsWhatEachVendorNeeds() {
        val withAccounts = mockk<ZcashAccounts> { every { accounts } returns listOf(mockk()) }
        val withoutAccounts = mockk<ZcashAccounts> { every { accounts } returns emptyList() }
        val session = KeystoneEnrollmentSession()
        val useCase = useCase(keystoneSession = session)

        assertTrue(useCase.isReady(HWWalletEnrollment.Keystone(session.start(withAccounts))))
        assertFalse(useCase.isReady(HWWalletEnrollment.Keystone(session.start(withoutAccounts))))
        assertFalse(useCase.isReady(HWWalletEnrollment.Keystone("expired")))

        assertTrue(useCase(hasPendingPairing = true).isReady(HWWalletEnrollment.Ledger))
        assertFalse(useCase(hasPendingPairing = false).isReady(HWWalletEnrollment.Ledger))

        assertTrue(useCase(hasPendingViewOnlyImport = true).isReady(HWWalletEnrollment.ViewOnly))
        assertFalse(useCase(hasPendingViewOnlyImport = false).isReady(HWWalletEnrollment.ViewOnly))
    }

    private fun useCase(
        keystoneSession: KeystoneEnrollmentSession = KeystoneEnrollmentSession(),
        hasPendingPairing: Boolean = true,
        hasPendingViewOnlyImport: Boolean = true,
        createKeystoneAccount: CreateKeystoneAccountUseCase = mockk(relaxed = true),
        ledgerAccountImporter: LedgerAccountImporter = mockk(relaxed = true),
        viewOnlyAccountImporter: ViewOnlyAccountImporter = mockk(relaxed = true),
        navigationRouter: NavigationRouter = mockk(relaxed = true),
    ) = CreateHWWalletAccountUseCase(
        keystoneSession = keystoneSession,
        createKeystoneAccount = createKeystoneAccount,
        ledgerAccountImporter =
            ledgerAccountImporter.also {
                every { it.hasPendingPairing() } returns hasPendingPairing
            },
        viewOnlyAccountImporter =
            viewOnlyAccountImporter.also {
                every { it.hasPendingImport() } returns hasPendingViewOnlyImport
            },
        navigationRouter = navigationRouter,
    )
}
