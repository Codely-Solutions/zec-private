package co.electriccoin.zcash.ui.common.repository

import android.content.Context
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MetadataDataSourceImpl
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.MetadataStorageProviderImpl
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataKey
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.util.SecretBytes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ResetMetadataScopeTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun resetDeletesActiveAndRemovedAccountFilesBeforeItCanSucceed() = runTest {
        val active = EditAccountFixtures.zodl()
        val accounts = mockk<AccountDataSource> {
            every { allAccounts } returns MutableStateFlow(listOf(active))
            every { selectedAccount } returns MutableStateFlow(active)
            coEvery { getAllAccounts() } returns listOf(active)
        }
        val filesDir = directory.newFolder("files")
        val context = mockk<Context> { every { this@mockk.filesDir } returns filesDir }
        var failSync = true
        var syncAttempts = 0
        val storage = MetadataStorageProviderImpl(context, DirectorySync {
            syncAttempts++
            if (failSync) throw IOException("Synthetic flush failure after deletion")
        })
        val activeFile = storage.getOrCreateStorageFile(metadataKey(1)).also { it.writeText("active") }
        val removedFile = storage.getOrCreateStorageFile(metadataKey(2)).also { it.writeText("removed") }
        val partial = removedFile.resolveSibling(removedFile.name + ".tmp").also { it.writeText("partial") }
        val undecodable = removedFile.resolveSibling(removedFile.name + ".undecodable-17")
        undecodable.writeText("aside")
        val unrelated = File(filesDir, "unrelated").also { it.writeText("preserve") }
        val datasource = MetadataDataSourceImpl(storage, mockk(), mockk())
        val repository = MetadataRepositoryImpl(accounts, datasource, mockk(), mockk(), mockk())

        assertFailsWith<IOException> { repository.delete() }
        listOf(activeFile, removedFile, partial, undecodable).forEach { assertFalse(it.exists()) }
        assertEquals("preserve", unrelated.readText())

        failSync = false
        repeat(2) { repository.delete() }
        assertEquals(3, syncAttempts)
        assertEquals("preserve", unrelated.readText())
        coVerify(exactly = 0) { accounts.getAllAccounts() }
    }

    private fun metadataKey(value: Byte): MetadataKey =
        MetadataKey(listOf(SecretBytes.copyFrom(ByteArray(32) { value }, InsecureSecretKeyAccess.get())))
}
