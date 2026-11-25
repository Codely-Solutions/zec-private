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
}
