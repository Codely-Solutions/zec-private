package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The note and bookmark use cases returning whether the edit was saved, showing the generic error
 * only when it was not, and retaining the editor until persistence is acknowledged.
 */
class TransactionMetadataEditUseCaseTest {
    private val owner = AccountUuid.new(ByteArray(16))
    private val metadataRepository = mockk<MetadataRepository>()
    private val navigationRouter = mockk<NavigationRouter>(relaxed = true)
    private val showError = mockk<ShowErrorUseCase>(relaxed = true)

    @Test
    fun savingANoteReturnsTheRepositoryResultAndGoesBack() =
        runTest {
            val useCase = CreateOrUpdateTransactionNoteUseCase(metadataRepository, navigationRouter, showError)
            coEvery { metadataRepository.createOrUpdateTxNote("tx1", "note", owner, 0) } returns false

            assertFalse(useCase("tx1", "  note  ", owner, 0))
            verify(exactly = 0) { navigationRouter.back() }
            verify(exactly = 1) { showError(any()) }

            coEvery { metadataRepository.createOrUpdateTxNote("tx1", "note", owner, 0) } returns true
            assertTrue(useCase("tx1", "note", owner, 0))
            verify(exactly = 1) { navigationRouter.back() }
            verify(exactly = 1) { showError(any()) }
        }

    @Test
    fun deletingANoteReturnsTheRepositoryResultAndGoesBack() =
        runTest {
            val useCase = DeleteTransactionNoteUseCase(metadataRepository, navigationRouter, showError)
            coEvery { metadataRepository.deleteTxNote("tx1", owner, 0) } returns false

            assertFalse(useCase("tx1", owner, 0))
            verify(exactly = 0) { navigationRouter.back() }
            verify(exactly = 1) { showError(any()) }

            coEvery { metadataRepository.deleteTxNote("tx1", owner, 0) } returns true
            assertTrue(useCase("tx1", owner, 0))
            verify(exactly = 1) { navigationRouter.back() }
            verify(exactly = 1) { showError(any()) }
        }

    @Test
    fun thrownSaveAndDeleteFailuresRetainTheEditor() = runTest {
        coEvery { metadataRepository.createOrUpdateTxNote(any(), any(), any(), any()) } throws IOException()
        coEvery { metadataRepository.deleteTxNote(any(), any(), any()) } throws IOException()
        assertFalse(CreateOrUpdateTransactionNoteUseCase(metadataRepository, navigationRouter, showError)("tx1", "note", owner, 0))
        assertFalse(DeleteTransactionNoteUseCase(metadataRepository, navigationRouter, showError)("tx1", owner, 0))
        verify(exactly = 0) { navigationRouter.back() }
        verify(exactly = 2) { showError(any()) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledEditorCannotNavigateAfterAnUninterruptibleWrite() = runTest {
        val result = CompletableDeferred<Boolean>()
        coEvery { metadataRepository.createOrUpdateTxNote(any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { result.await() }
        }
        val save = launch {
            CreateOrUpdateTransactionNoteUseCase(metadataRepository, navigationRouter, showError)("tx1", "note", owner, 0)
        }
        runCurrent()
        save.cancel()
        result.complete(true)
        save.join()
        verify(exactly = 0) { navigationRouter.back() }
        verify(exactly = 0) { showError(any()) }
    }

    @Test
    fun settingABookmarkReturnsTheRepositoryResult() =
        runTest {
            val account = AccountUuid.new(ByteArray(16))
            val useCase = SetTransactionBookmarkUseCase(metadataRepository, showError)
            coEvery { metadataRepository.setTxBookmark("tx1", true, account, 0) } returns false

            assertFalse(useCase("tx1", true, account, 0))
            verify(exactly = 1) { showError(any()) }

            coEvery { metadataRepository.setTxBookmark("tx1", true, account, 0) } returns true
            assertTrue(useCase("tx1", true, account, 0))
            verify(exactly = 1) { showError(any()) }
        }
}
