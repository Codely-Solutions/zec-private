package co.electriccoin.zcash.ui.common.usecase

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.DistributionDimension
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharePCZTUseCaseTest {
    @Test
    fun newHandoffsUseUniqueFilesAndDiscardARevokedExport() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val proposals = mockk<KeystoneProposalRepository>()
        every { proposals.getProposalPCZT() } returnsMany listOf(pczt("first"), pczt("second"), pczt("revoked"))
        val plan = mockk<VersionInfo> {
            every { network } returns ZcashNetwork.Mainnet
            every { distribution } returns DistributionDimension.FOSS
            every { isDebuggable } returns true
        }
        val version = mockk<GetVersionInfoProvider> { every { this@mockk.invoke() } returns plan }
        val files = mutableMapOf<Uri, File>()
        val scheduled = mutableListOf<File>()
        val access = AppAccessSession().apply { unlock() }
        val share = SharePCZTUseCase(app, proposals, version, access, WalletWriteAccess()).apply {
            scheduleCleanup = { staging ->
                assertTrue(staging.listFiles().orEmpty().isEmpty())
                scheduled += staging
                if (scheduled.size == 3) {
                    access.lock()
                    access.unlock()
                }
            }
        }
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockkStatic(FileProvider::class)
        try {
            every { FileProvider.getUriForFile(app, any(), any()) } answers {
                val file = thirdArg<File>()
                val uri = Uri.parse("content://synthetic/${file.parentFile?.name}/${file.name}")
                files[uri] = file
                uri
            }
            share()
            val first = sharedUri(shadowOf(app).nextStartedActivity)
            share()
            val second = sharedUri(shadowOf(app).nextStartedActivity)
            assertNotEquals(first, second)
            assertEquals("first", files.getValue(first).readText())
            assertEquals("second", files.getValue(second).readText())

            share()
            assertNull(shadowOf(app).nextStartedActivity)
            assertEquals(3, scheduled.size)
            assertFalse(scheduled.last().exists())
            assertEquals("first", files.getValue(first).readText())
            assertEquals("second", files.getValue(second).readText())
        } finally {
            for (staging in scheduled) staging.deleteRecursively()
            unmockkStatic(FileProvider::class)
            Dispatchers.resetMain()
        }
    }

    private fun sharedUri(chooser: Intent): Uri {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        return checkNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    }

    private fun pczt(text: String): Pczt = mockk { every { toByteArray() } returns text.toByteArray() }
}
