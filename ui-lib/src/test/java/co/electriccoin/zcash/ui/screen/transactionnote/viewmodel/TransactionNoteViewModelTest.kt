package co.electriccoin.zcash.ui.screen.transactionnote.viewmodel

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.usecase.CreateOrUpdateTransactionNoteUseCase
import co.electriccoin.zcash.ui.common.usecase.DeleteTransactionNoteUseCase
import co.electriccoin.zcash.ui.common.usecase.GetTransactionMetadataUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.transactionnote.TransactionNote
import co.electriccoin.zcash.ui.screen.transactionnote.model.TransactionNoteState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The note sheet running one save at a time and showing loading on the button that started it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionNoteViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private val owner = AccountUuid.new(ByteArray(16))
    private val route = TransactionNote(TX_ID, owner.toStorageKeyId(), 0)
    private val navigationRouter = mockk<NavigationRouter>(relaxed = true)
    private val saveResult = CompletableDeferred<Boolean>()
    private val metadataRepository =
        mockk<MetadataRepository> {
            coEvery { createOrUpdateTxNote(any(), any(), any(), any()) } coAnswers { saveResult.await() }
            coEvery { deleteTxNote(any(), any(), any()) } coAnswers { saveResult.await() }
        }
    private val createOrUpdate =
        CreateOrUpdateTransactionNoteUseCase(metadataRepository, navigationRouter, mockk(relaxed = true))
    private val delete = DeleteTransactionNoteUseCase(metadataRepository, navigationRouter, mockk(relaxed = true))

    @BeforeTest
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun repeatedCallbacksDuringAndAfterSaveStartNoOtherWrite() =
        runTest(dispatcher) {
            val vm = startedVm()

            val save = requireNotNull(vm.state().secondaryButton)
            val delete = requireNotNull(vm.state().negative)
            save.onClick()
            advanceUntilIdle()
            save.onClick()
            delete.onClick()
            advanceUntilIdle()

            coVerify(exactly = 1) { metadataRepository.createOrUpdateTxNote(TX_ID, NOTE, owner, 0) }
            coVerify(exactly = 0) { metadataRepository.deleteTxNote(any(), any(), any()) }

            saveResult.complete(true)
            advanceUntilIdle()
            assertFalse(requireNotNull(vm.state().secondaryButton).isLoading)
            save.onClick()
            delete.onClick()
            advanceUntilIdle()
            coVerify(exactly = 1) { metadataRepository.createOrUpdateTxNote(TX_ID, NOTE, owner, 0) }
            coVerify(exactly = 0) { metadataRepository.deleteTxNote(any(), any(), any()) }
            verify(exactly = 1) { navigationRouter.back() }
        }

    @Test
    fun backIsIgnoredWhileASaveRuns() =
        runTest(dispatcher) {
            val vm = startedVm()

            requireNotNull(vm.state().secondaryButton).onClick()
            advanceUntilIdle()
            vm.state().onBack()
            verify(exactly = 0) { navigationRouter.back() }

            saveResult.complete(true)
            advanceUntilIdle()
            verify(exactly = 1) { navigationRouter.back() }
            vm.state().onBack()
            verify(exactly = 1) { navigationRouter.back() }
        }

    @Test
    fun aFailedSaveClearsLoadingAndRetiresTheEditorAsItCloses() =
        runTest(dispatcher) {
            val vm = startedVm()

            val save = requireNotNull(vm.state().secondaryButton)
            save.onClick()
            advanceUntilIdle()
            assertTrue(requireNotNull(vm.state().secondaryButton).isLoading)

            saveResult.complete(false)
            advanceUntilIdle()

            val completed = requireNotNull(vm.state().secondaryButton)
            assertFalse(completed.isLoading)
            assertFalse(completed.isEnabled)
            save.onClick()
            advanceUntilIdle()
            coVerify(exactly = 1) { metadataRepository.createOrUpdateTxNote(TX_ID, NOTE, owner, 0) }
            verify(exactly = 1) { navigationRouter.back() }
        }

    @Test
    fun thePressedButtonShowsLoadingUntilTheSaveReturns() =
        runTest(dispatcher) {
            val vm = startedVm()

            val delete = requireNotNull(vm.state().negative)
            val save = requireNotNull(vm.state().secondaryButton)
            delete.onClick()
            advanceUntilIdle()

            assertTrue(requireNotNull(vm.state().negative).isLoading)
            assertFalse(requireNotNull(vm.state().secondaryButton).isLoading)
            assertTrue(vm.state().isSaving)

            saveResult.complete(true)
            advanceUntilIdle()

            assertFalse(vm.state().isSaving)

            val completed = requireNotNull(vm.state().negative)
            assertFalse(completed.isLoading)
            assertFalse(completed.isEnabled)
            delete.onClick()
            save.onClick()
            advanceUntilIdle()
            coVerify(exactly = 1) { metadataRepository.deleteTxNote(TX_ID, owner, 0) }
            coVerify(exactly = 0) { metadataRepository.createOrUpdateTxNote(any(), any(), any(), any()) }
            verify(exactly = 1) { navigationRouter.back() }
        }

    @Test
    fun aDelayedReadKeepsTheEditorLockedUntilItLoadsTheStoredNote() = runTest(dispatcher) {
        val metadataRead = CompletableDeferred<String?>()
        val vm = startedVm { metadataRead.await() }

        val loading = vm.state()
        assertNotNull(loading.loadMessage)
        assertFalse(loading.note.isEnabled)
        assertNull(loading.primaryButton)
        assertNull(loading.secondaryButton)
        assertNull(loading.negative)
        loading.note.onValueChange("Draft during load")

        metadataRead.complete(NOTE)
        advanceUntilIdle()

        loading.note.onValueChange("Draft from stale callback")
        advanceUntilIdle()
        assertEquals(stringRes(NOTE), vm.state().note.value)
        assertTrue(vm.state().note.isEnabled)
        assertNull(vm.state().loadMessage)
        assertNotNull(vm.state().secondaryButton)
        assertNotNull(vm.state().negative)
    }

    @Test
    fun aReadFailureOffersRetryAndAnEmptyResultAllowsAddingANote() = runTest(dispatcher) {
        var reads = 0
        val vm = startedVm {
            if (++reads == 1) throw IOException("read failed")
            null
        }

        val failed = vm.state()
        assertNotNull(failed.loadMessage)
        assertFalse(failed.note.isEnabled)
        assertNull(failed.primaryButton)
        val retry = assertNotNull(failed.retry)
        retry.onClick()
        retry.onClick()
        advanceUntilIdle()

        val add = assertNotNull(vm.state().primaryButton)
        assertFalse(add.isEnabled)
        add.onClick()
        coVerify(exactly = 0) { createOrUpdate(any(), any(), any(), any()) }
        assertNull(vm.state().negative)
        vm.state().note.onValueChange("New draft")
        advanceUntilIdle()
        val save = assertNotNull(vm.state().primaryButton)
        assertTrue(save.isEnabled)
        save.onClick()
        saveResult.complete(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { metadataRepository.createOrUpdateTxNote(TX_ID, "New draft", owner, 0) }
        assertEquals(2, reads)
    }

    @Test
    fun aRetainedSaveCallbackCannotWriteAfterLeavingTheSheet() = runTest(dispatcher) {
        val vm = startedVm()
        val previous = assertNotNull(vm.state().secondaryButton)
        vm.state().onBack()
        previous.onClick()
        advanceUntilIdle()

        verify(exactly = 1) { navigationRouter.back() }
        coVerify(exactly = 0) { metadataRepository.createOrUpdateTxNote(any(), any(), any(), any()) }
    }

    private fun TestScope.startedVm(readNote: suspend () -> String? = { NOTE }): TransactionNoteViewModel {
        val vm = TransactionNoteViewModel(
            transactionNote = route,
            navigationRouter = navigationRouter,
            getTransactionNote = mockk {
                coEvery { this@mockk.invoke(TX_ID, owner, 0) } coAnswers { readNote() }
            },
            createOrUpdateTransactionNote = createOrUpdate,
            deleteTransactionNote = delete,
        )
        backgroundScope.launch { vm.state.collect { } }
        advanceUntilIdle()
        return vm
    }

    private fun TransactionNoteViewModel.state(): TransactionNoteState = state.value

    private companion object {
        const val TX_ID = "tx1"
        const val NOTE = "note"
    }
}
