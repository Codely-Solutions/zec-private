package co.electriccoin.zcash.ui.common.usecase

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.util.FileShareUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShareImageUseCaseTest {
    @get:Rule val directory = TemporaryFolder()

    private val context = mockk<Context>()
    private val intent = mockk<Intent>()
    private val versionInfo = mockk<GetVersionInfoProvider> { every { this@mockk.invoke() } returns mockk() }
    private val writeAccess = WalletWriteAccess()
    private val share = ShareImageUseCase(context, versionInfo, writeAccess)

    @BeforeTest
    fun setUp() {
        share.scheduleCleanup = { file -> assertEquals(0L, file.length()) }
        every { context.cacheDir } returns directory.root
        mockkObject(FileShareUtil)
        every {
            FileShareUtil.newShareContentIntent(context, any<File>(), any(), any(), any(), any())
        } returns intent
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(FileShareUtil)
    }

    @Test
    fun chooserFailureRemovesTheStagedImageAndPreservesUnrelatedFiles() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val unrelated = createUnrelatedFile()
        every { context.startActivity(intent) } answers {
            assertTrue(stagedFiles().single().length() > 0)
            throw ActivityNotFoundException()
        }

        assertFalse(shareKey(bitmap()))

        assertTrue(stagedFiles().isEmpty())
        assertEquals("keep", unrelated.readText())
        verify(exactly = 1) { context.startActivity(intent) }
    }

    @Test
    fun cancellationDuringStagingRemovesTheImageWithoutLaunching() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val unrelated = createUnrelatedFile()
        val operation = launch(start = CoroutineStart.LAZY) {
            val owner = checkNotNull(coroutineContext[Job])
            shareKey(bitmap { owner.cancel() })
        }

        operation.join()

        assertTrue(operation.isCancelled)
        assertTrue(stagedFiles().isEmpty())
        assertEquals("keep", unrelated.readText())
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun aDeniedSharePreservesThePreviouslyHandedOffQr() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        every { context.startActivity(intent) } returns Unit
        assertTrue(shareKey(bitmap()))
        val first = stagedFiles().single()
        val bytes = first.readBytes().toList()

        assertFalse(shareKey(bitmap(), isAllowed = { false }))
        assertEquals(listOf(first), stagedFiles().toList())
        assertEquals(bytes, first.readBytes().toList())
        verify(exactly = 1) { context.startActivity(intent) }
    }

    @Test
    fun aRevokedGrantCleansStagingAndResetBlocksNewKeyShares() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        assertFalse(shareKey(bitmap(), isAllowed = { false }))
        assertTrue(stagedFiles().isEmpty())

        writeAccess.beginReset()
        val bitmap = bitmap()
        assertFalse(shareKey(bitmap))

        verify(exactly = 0) { bitmap.compress(any(), any(), any()) }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    private suspend fun shareKey(bitmap: Bitmap, isAllowed: () -> Boolean = { true }) =
        share(
            bitmap,
            sharePickerText = "Synthetic share",
            filePrefix = VK_QR_FILENAME_PREFIX,
            fileSuffix = ".png",
            isAllowed = isAllowed,
        )

    private fun bitmap(onWrite: () -> Unit = {}) = mockk<Bitmap> {
        every { compress(any(), any(), any()) } answers {
            thirdArg<OutputStream>().write(1)
            onWrite()
            true
        }
    }

    private fun cache() = directory.root.resolve(CACHE_SUBDIR)

    private fun createUnrelatedFile() =
        cache().apply { mkdir() }.resolve("unrelated.png").apply { writeText("keep") }

    private fun stagedFiles() = cache().listFiles { file -> file.name.startsWith(VK_QR_FILENAME_PREFIX) }.orEmpty()
}
