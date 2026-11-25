package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SelectedAccountUUIDProviderTest {
    @Test
    fun selectionDuringDeletionOutlastsTheConditionalClear() = runTest {
        val deleted = AccountUuid.new(ByteArray(16))
        val successor = AccountUuid.new(ByteArray(16) { 1 })
        val readingDeletedSelection = CompletableDeferred<Unit>()
        val finishReading = CompletableDeferred<Unit>()
        val key = PreferenceKey("selected_account_uuid")
        var stored: String? = null
        val preferences = mockk<PreferenceProvider> {
            coEvery { putStringWithRollback(key, any()) } coAnswers {
                stored = secondArg<String?>()
            }
            coEvery { getString(key) } coAnswers {
                val snapshot = stored
                readingDeletedSelection.complete(Unit)
                finishReading.await()
                snapshot
            }
        }
        val provider = SelectedAccountUUIDProviderImpl(
            mockk<EncryptedPreferenceProvider> { coEvery { this@mockk() } returns preferences }
        )
        provider.setUUID(deleted)

        val clear = async { provider.clearIfSelected(deleted) }
        readingDeletedSelection.await()
        val select = async { provider.setUUID(successor) }
        runCurrent()
        assertFalse(select.isCompleted)
        finishReading.complete(Unit)
        clear.await()
        select.await()

        assertEquals(successor, provider.getUUID())
        provider.clearIfSelected(deleted)
        assertEquals(successor, provider.getUUID())
    }
    @Test
    fun anApprovalIsRevokedBeforeTheSelectionWriteCanPublishToObservers() = runTest {
        val old = AccountUuid.new(ByteArray(16))
        val replacement = AccountUuid.new(ByteArray(16) { 2 })
        val writing = CompletableDeferred<Unit>()
        val persisted = CompletableDeferred<Unit>()
        val key = PreferenceKey("selected_account_uuid")
        var stored: String? = null
        val preferences = mockk<PreferenceProvider> {
            coEvery { putStringWithRollback(key, any()) } coAnswers {
                if (stored != null) {
                    writing.complete(Unit)
                    persisted.await()
                }
                stored = secondArg<String?>()
            }
            coEvery { getString(key) } coAnswers { stored }
        }
        val provider = SelectedAccountUUIDProviderImpl(
            mockk<EncryptedPreferenceProvider> { coEvery { this@mockk() } returns preferences }
        )
        provider.setUUID(old)
        val prior = provider.captureSelection()
        assertTrue(provider.isCurrent(prior))

        val change = async { provider.setUUID(replacement) }
        writing.await()
        assertFalse(provider.isCurrent(prior))
        persisted.complete(Unit)
        change.await()

        val current = provider.captureSelection()
        assertEquals(replacement, current.uuid)
        assertTrue(provider.isCurrent(current))
    }

}
