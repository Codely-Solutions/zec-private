package co.electriccoin.zcash.ui.common.usecase

import androidx.compose.ui.graphics.Color
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccountAlreadyExistsException
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccountImportException
import co.electriccoin.zcash.ui.common.model.ViewOnlyPendingImportMissingException
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.PendingViewOnlyImport
import co.electriccoin.zcash.ui.common.repository.ViewOnlyPendingImportRepositoryImpl
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenArgs
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenFlow
import co.electriccoin.zcash.ui.screen.viewingkey.alreadyimported.AlreadyImportedVKArgs
import co.electriccoin.zcash.ui.screen.viewingkey.imported.ImportedVKArgs
import co.electriccoin.zcash.ui.screen.viewingkey.name.NameVKArgs
import co.electriccoin.zcash.viewingkey.ViewOnlyAccountImporterImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The import imports the pending key under its name, selects the new account, forgets the key and
 * restarts the synchronizer, then shows the success screen, through the keep-open screen when there
 * is history to scan. A key the wallet already holds ends on the "already imported" sheet; nothing
 * pending fails without touching the wallet; an SDK failure keeps the key so the import can be
 * retried. A caller cancelled mid-import still gets the account selected, but no navigation. A picked
 * colour is stored for the new account once the wallet lists it, and a store that hangs is given up
 * without holding back the import. The birthday is the one the pending import holds. The importer
 * ui-lib's birthday screens call stores that birthday with the pending import and opens the naming
 * step, which imports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewOnlyAccountUseCaseTest {
    private val repository = ViewOnlyPendingImportRepositoryImpl()
    private val wallet = WalletWriteAccess()
    private val sdk = mockk<Synchronizer>()
    private val activeSdk = MutableStateFlow<Synchronizer?>(sdk)
    private val walletEnrollment = ViewOnlyAccountFixtures.enrollmentAccess(wallet, activeSdk)

    private val created = mockk<Account> { every { accountUuid } returns ViewOnlyAccountFixtures.viewOnlyUuid }

    private val accountDataSource =
        mockk<AccountDataSource>(relaxed = true) {
            coEvery { importViewOnlyAccount(sdk, any(), any(), any()) } returns created
        }

    private val synchronizerProvider = mockk<SynchronizerProvider>(relaxed = true)

    private val navigationRouter = mockk<NavigationRouter>(relaxed = true)

    private val metadataRepository = mockk<MetadataRepository>(relaxed = true)

    private val useCase =
        ImportViewOnlyAccountUseCase(
            repository,
            accountDataSource,
            synchronizerProvider,
            navigationRouter,
            metadataRepository,
            walletEnrollment,
        )

    @Test
    fun walletResetCanFinishWhenTheSdkIsUnavailableAndOldEnrollmentCannotUseItsReplacement() = runTest {
        repository.set(pending(name = "Savings"))
        activeSdk.value = null
        assertFailsWith<IllegalStateException> { useCase() }
        assertEquals("Savings", repository.get()?.name)

        wallet.beginReset()
        wallet.finishReset()
        wallet.replaceWallet {}
        activeSdk.value = mockk()
        assertFailsWith<ViewOnlyPendingImportMissingException> { useCase() }

        coVerify(exactly = 0) { accountDataSource.importViewOnlyAccount(any(), any(), any(), any()) }
        coVerify(exactly = 0) { accountDataSource.selectAccount(any<Account>()) }
        verify(exactly = 0) { navigationRouter.forward(any()) }
        assertNull(repository.get())
    }

    @Test
    fun resetDrainsAnAdmittedSdkImportAndRejectsLateSelection() = runTest {
        repository.set(pending(name = "Savings"))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Account>()
        coEvery { accountDataSource.importViewOnlyAccount(sdk, any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            finish.await()
        }

        val importing = async { runCatching { useCase() } }
        entered.await()
        val resetting = async { wallet.beginReset() }
        runCurrent()
        assertFalse(resetting.isCompleted)
        finish.complete(created)
        assertTrue(importing.await().exceptionOrNull() is ViewOnlyPendingImportMissingException)
        resetting.await()
        wallet.finishReset()

        coVerify(exactly = 0) { accountDataSource.selectAccount(any<Account>()) }
        verify(exactly = 0) { synchronizerProvider.resetSynchronizer() }
        verify(exactly = 0) { navigationRouter.forward(any()) }
        assertNull(repository.get())
    }

    @Test
    fun anOrdinarySynchronizerRestartKeepsTheExistingImportReceipt() = runTest {
        repository.set(pending(name = "Savings").copy(importedAccount = created))
        activeSdk.value = mockk()

        useCase()

        coVerify(exactly = 0) { accountDataSource.importViewOnlyAccount(any(), any(), any(), any()) }
        coVerify(exactly = 1) { accountDataSource.selectAccount(created) }
        verify(exactly = 1) { navigationRouter.forward(ImportedVKArgs) }
        assertNull(repository.get())
    }

    @Test
    fun anAccountWithoutHistoryGoesStraightToTheSuccessScreen() =
        runTest {
            repository.set(pending(name = "Savings"))

            useCase()

            coVerify(exactly = 1) { accountDataSource.importViewOnlyAccount(sdk, UFVK, "Savings", null) }
            coVerify(exactly = 1) { accountDataSource.selectAccount(created) }
            verify(exactly = 1) { synchronizerProvider.resetSynchronizer() }
            verify(exactly = 1) { navigationRouter.forward(ImportedVKArgs) }
            assertNull(repository.get())
            coVerify(exactly = 0) { metadataRepository.setAccountColor(any(), any(), any()) }
        }

    @Test
    fun aPickedColorIsStoredForTheNewAccountBeforeTheSynchronizerRestarts() =
        runTest {
            val account = ViewOnlyAccountFixtures.viewOnly()
            every { accountDataSource.allAccounts } returns MutableStateFlow(listOf(account))
            repository.set(pending(name = "Savings", color = Color(PICKED)))

            useCase()

            coVerifyOrder {
                accountDataSource.selectAccount(created)
                metadataRepository.setAccountColor(account, Color(PICKED), walletEnrollment.capture())
                synchronizerProvider.resetSynchronizer()
            }
            verify(exactly = 1) { navigationRouter.forward(ImportedVKArgs) }
        }

    @Test
    fun aColorForAnAccountTheWalletNeverListsIsDroppedAndTheImportGoesOn() =
        runTest {
            every { accountDataSource.allAccounts } returns MutableStateFlow(emptyList())
            repository.set(pending(name = "Savings", color = Color(PICKED)))

            useCase()

            coVerify(exactly = 0) { metadataRepository.setAccountColor(any(), any(), any()) }
            verify(exactly = 1) { synchronizerProvider.resetSynchronizer() }
            verify(exactly = 1) { navigationRouter.forward(ImportedVKArgs) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aColorWriteThatNeverReturnsStillLetsTheImportFinish() =
        runTest {
            val account = ViewOnlyAccountFixtures.viewOnly()
            every { accountDataSource.allAccounts } returns MutableStateFlow(listOf(account))
            coEvery { metadataRepository.setAccountColor(any(), any(), any()) } coAnswers { awaitCancellation() }
            repository.set(pending(name = "Savings", color = Color(PICKED)))

            useCase()

            coVerify(exactly = 1) {
                metadataRepository.setAccountColor(account, Color(PICKED), walletEnrollment.capture())
            }
            coVerify(exactly = 1) { accountDataSource.selectAccount(created) }
            verify(exactly = 1) { synchronizerProvider.resetSynchronizer() }
            verify(exactly = 1) { navigationRouter.forward(ImportedVKArgs) }
            assertNull(repository.get())
            assertEquals(COLOR_BUDGET_MILLIS, currentTime)
        }

    @Test
    fun aFailedViewOnlyFinalizationRejectsAnEarlierBirthdayAndRetriesTheCommittedHeight() = runTest {
        val importedHeight = BlockHeight.new(2_500_000L)
        val correctedHeight = BlockHeight.new(2_000_000L)
        repository.set(pending(name = "Savings", birthday = importedHeight))
        coEvery { accountDataSource.selectAccount(created) } throws IOException() andThen Unit
        assertFailsWith<IOException> { useCase() }

        val importer = ViewOnlyAccountImporterImpl(repository, navigationRouter, useCase)
        importer.continueWithBirthday(correctedHeight)

        verify(exactly = 0) { navigationRouter.forward(NameVKArgs) }
        assertEquals(importedHeight, importer.committedBirthday()?.height)
        assertEquals(importedHeight, repository.get()?.birthday)
        importer.finishImport()
        coVerify(exactly = 1) { accountDataSource.importViewOnlyAccount(sdk, UFVK, "Savings", importedHeight) }
        coVerify(exactly = 0) { accountDataSource.importViewOnlyAccount(sdk, UFVK, "Savings", correctedHeight) }
        verify(exactly = 1) { navigationRouter.forward(KeepOpenArgs(KeepOpenFlow.VIEW_ONLY)) }
    }

    @Test
    fun anAccountWithHistoryGoesThroughTheKeepOpenScreen() =
        runTest {
            val birthday = BlockHeight.new(2_000_000L)
            repository.set(pending(name = "Savings", birthday = birthday))

            useCase()

            coVerify(exactly = 1) { accountDataSource.importViewOnlyAccount(sdk, UFVK, "Savings", birthday) }
            verify(exactly = 1) { navigationRouter.forward(KeepOpenArgs(KeepOpenFlow.VIEW_ONLY)) }
            assertNull(repository.get())
        }

    @Test
    fun aKeyTheWalletAlreadyHoldsEndsOnTheAlreadyImportedSheet() =
        runTest {
            repository.set(pending(name = "Savings"))
            coEvery { accountDataSource.importViewOnlyAccount(sdk, any(), any(), any()) } throws
                ViewOnlyAccountAlreadyExistsException()

            useCase()

            verify(exactly = 1) { navigationRouter.forward(AlreadyImportedVKArgs) }
            coVerify(exactly = 0) { accountDataSource.selectAccount(any<Account>()) }
            verify(exactly = 0) { synchronizerProvider.resetSynchronizer() }
            assertNull(repository.get())
        }

    @Test
    fun anSdkFailureKeepsTheKeySoTheImportCanBeRetried() =
        runTest {
            repository.set(pending(name = "Savings"))
            coEvery { accountDataSource.importViewOnlyAccount(sdk, any(), any(), any()) } throws
                ViewOnlyAccountImportException()

            val error = assertFailsWith<ViewOnlyAccountImportException> { useCase() }

            assertFalse(error.stackTraceToString().contains("uview"))
            assertEquals(UFVK, repository.get()?.ufvk)
            verify(exactly = 0) { navigationRouter.forward(any()) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aSecondCallWhileTheFirstImportsReturnsWithoutImportingAgain() =
        runTest {
            repository.set(pending(name = "Savings"))
            val imported = CompletableDeferred<Account>()
            coEvery { accountDataSource.importViewOnlyAccount(sdk, any(), any(), any()) } coAnswers { imported.await() }

            val first = async { useCase() }
            val second = async { useCase() }
            runCurrent()
            imported.complete(created)
            first.await()
            second.await()

            coVerify(exactly = 1) { accountDataSource.importViewOnlyAccount(sdk, any(), any(), any()) }
            coVerify(exactly = 1) { accountDataSource.selectAccount(created) }
            verify(exactly = 1) { synchronizerProvider.resetSynchronizer() }
            verify(exactly = 1) { navigationRouter.forward(ImportedVKArgs) }
            verify(exactly = 0) { navigationRouter.forward(AlreadyImportedVKArgs) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledCallerStillSelectsTheImportedAccountButDoesNotNavigate() =
        runTest {
            repository.set(pending(name = "Savings"))
            val imported = CompletableDeferred<Account>()
            coEvery { accountDataSource.importViewOnlyAccount(sdk, any(), any(), any()) } coAnswers { imported.await() }

            val caller = launch { useCase() }
            runCurrent()
            caller.cancel()
            imported.complete(created)
            caller.join()

            coVerify(exactly = 1) { accountDataSource.selectAccount(created) }
            verify(exactly = 1) { synchronizerProvider.resetSynchronizer() }
            assertNull(repository.get())
            verify(exactly = 0) { navigationRouter.forward(any()) }
        }

    @Test
    fun aSecondCallAfterAFailedImportRetriesIt() =
        runTest {
            repository.set(pending(name = "Savings"))
            coEvery { accountDataSource.importViewOnlyAccount(sdk, any(), any(), any()) } throws
                ViewOnlyAccountImportException() andThen created

            assertFailsWith<ViewOnlyAccountImportException> { useCase() }
            useCase()

            coVerify(exactly = 2) { accountDataSource.importViewOnlyAccount(sdk, UFVK, "Savings", null) }
            verify(exactly = 1) { navigationRouter.forward(ImportedVKArgs) }
            assertNull(repository.get())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationDuringAFailedSelectionKeepsTheCreatedAccountAvailableForRetry() = runTest {
        repository.set(pending(name = "Savings"))
        val finishWrite = CompletableDeferred<Unit>()
        coEvery { accountDataSource.selectAccount(created) } coAnswers {
            finishWrite.await()
            throw IOException()
        }

        val caller = launch {
            try {
                useCase()
            } catch (_: IOException) {
                // The original screen can leave while its acknowledged write is pending.
            }
        }
        runCurrent()
        caller.cancel()
        finishWrite.complete(Unit)
        caller.join()

        assertEquals(created, repository.get()?.importedAccount)
        verify(exactly = 0) { navigationRouter.forward(*anyVararg()) }
        verify(exactly = 0) { synchronizerProvider.resetSynchronizer() }

        coEvery { accountDataSource.selectAccount(created) } returns Unit
        useCase()

        coVerify(exactly = 1) { accountDataSource.importViewOnlyAccount(sdk, any(), any(), any()) }
        coVerify(exactly = 2) { accountDataSource.selectAccount(created) }
        verify(exactly = 1) { synchronizerProvider.resetSynchronizer() }
        verify(exactly = 1) { navigationRouter.forward(ImportedVKArgs) }
        assertNull(repository.get())
    }

    @Test
    fun nothingPendingFailsWithoutTouchingTheWallet() =
        runTest {
            assertFailsWith<ViewOnlyPendingImportMissingException> { useCase() }

            coVerify(exactly = 0) { accountDataSource.importViewOnlyAccount(sdk, any(), any(), any()) }
            verify(exactly = 0) { synchronizerProvider.resetSynchronizer() }
        }

    @Test
    fun theImporterReportsThePendingKey() {
        val importer = ViewOnlyAccountImporterImpl(repository, navigationRouter, useCase)
        assertFalse(importer.hasPendingImport())

        repository.set(pending(name = ""))

        assertTrue(importer.hasPendingImport())
    }

    @Test
    fun theImporterStoresTheBirthdayAndOpensTheNamingStepWithoutImporting() {
        val importer = ViewOnlyAccountImporterImpl(repository, navigationRouter, useCase)
        repository.set(pending(name = "Savings", color = Color(PICKED)))
        val birthday = BlockHeight.new(2_000_000L)

        importer.continueWithBirthday(birthday)

        assertEquals(
            pending(name = "Savings", color = Color(PICKED), birthday = birthday),
            repository.get()
        )
        verify(exactly = 1) { navigationRouter.forward(NameVKArgs) }
        coVerify(exactly = 0) { accountDataSource.importViewOnlyAccount(sdk, any(), any(), any()) }
    }

    @Test
    fun theImporterFailsWhenNothingIsPending() {
        val importer = ViewOnlyAccountImporterImpl(repository, navigationRouter, useCase)

        assertFailsWith<ViewOnlyPendingImportMissingException> { importer.continueWithBirthday(null) }

        assertNull(repository.get())
        verify(exactly = 0) { navigationRouter.forward(any()) }
    }

    private fun pending(
        name: String,
        color: Color? = null,
        birthday: BlockHeight? = null,
    ) = PendingViewOnlyImport(
        ufvk = UFVK,
        name = name,
        color = color,
        birthday = birthday,
        enrollment = walletEnrollment.capture(),
    )

    @Test
    fun thePendingImportNeverPrintsItsKey() {
        val birthday = BlockHeight.new(2_000_000L)

        val printed = pending(name = "Savings", birthday = birthday).toString()

        assertFalse(printed.contains("uview"))
        assertTrue(printed.contains("birthday=$birthday"))
    }

    private companion object {
        const val UFVK = "uview1testkeythatmustneverbeprinted"
        const val PICKED = 0x80BE38F3
        const val COLOR_BUDGET_MILLIS = 5_000L
    }
}
