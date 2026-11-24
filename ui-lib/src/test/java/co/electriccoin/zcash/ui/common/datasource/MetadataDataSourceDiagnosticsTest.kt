package co.electriccoin.zcash.ui.common.datasource

import co.electriccoin.zcash.ui.common.provider.MetadataProvider
import co.electriccoin.zcash.ui.common.provider.MetadataStorageProvider
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.nio.file.AccessDeniedException
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetadataDataSourceDiagnosticsTest {
    @Test
    fun unreadableFileRetainsRecoveryWithoutLoggingItsAccountIdentifier() = runTest {
        val file = File("synthetic", "synthetic-account-identifier")
        val key = mockk<MetadataKey> {
            every { fileIdentifier() } returns file.name
            every { fileIdentifiers() } returns listOf(file.name)
        }
        val storage = mockk<MetadataStorageProvider> {
            every { getStorageFiles(key) } returns listOf(file)
            every { getOrCreateStorageFile(key) } returns file
            every { sizeOf(file) } returns 1L
        }
        val provider = mockk<MetadataProvider> {
            every { readMetadataFromFile(file, key) } throws AccessDeniedException(file.path)
        }
        val dataSource = MetadataDataSourceImpl(storage, provider, mockk(), StandardTestDispatcher(testScheduler))
        ShadowLog.clear()

        assertNull(dataSource.read(key))
        verify(atLeast = 2) { provider.readMetadataFromFile(file, key) }
        verify(exactly = 0) { storage.setAsideUndecodable(file) }
        val logs = ShadowLog.getLogs()
        assertTrue(logs.any { "Failed to read metadata (AccessDeniedException)" in it.msg })
        assertTrue(logs.none { file.name in it.msg || file.name in it.throwable?.stackTraceToString().orEmpty() })
    }
}
