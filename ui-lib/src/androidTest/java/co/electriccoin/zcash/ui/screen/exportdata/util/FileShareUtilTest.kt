package co.electriccoin.zcash.ui.screen.exportdata.util

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import co.electriccoin.zcash.ui.util.FileShareUtil
import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SmallTest
class FileShareUtilTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val authority = "co.electriccoin.zcash.provider_test"

    @Test
    fun approved_share_roots_can_be_read_through_provider() {
        listOf("zodl_qr_images", "tax_exports", "pczt_exports", "diagnostic_exports").forEach { directory ->
            withSyntheticFile(File(context.cacheDir, directory)) { file ->
                val uri = FileProvider.getUriForFile(context, authority, file)
                assertEquals("content", uri.scheme)
                assertEquals("synthetic share fixture", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            }
        }
    }

    @Test
    fun unrelated_private_files_cannot_obtain_share_uris() {
        listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir, File(context.cacheDir, "zodl_qr_images_other")).forEach { directory ->
            withSyntheticFile(directory) { file ->
                assertFailsWith<IllegalArgumentException> { FileProvider.getUriForFile(context, authority, file) }
            }
        }
    }

    @Test
    fun chooser_and_payload_grant_read_access_to_the_same_qr_uri() {
        withSyntheticFile(File(context.cacheDir, "zodl_qr_images")) { file ->
            val sharedUri = FileProvider.getUriForFile(context, authority, file)
            val chooser = FileShareUtil.newShareContentIntent(
                fileUri = sharedUri,
                fileType = FileShareUtil.ZASHI_QR_CODE_MIME_TYPE,
                shareText = "Synthetic request",
                sharePickerText = "Share fixture"
            )
            @Suppress("DEPRECATION")
            val payload = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            @Suppress("DEPRECATION")
            val uri = payload.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertEquals(Intent.ACTION_SEND, payload.action)
            assertEquals(FileShareUtil.ZASHI_QR_CODE_MIME_TYPE, payload.type)
            assertEquals(sharedUri, uri)
            assertEquals(uri, payload.clipData!!.getItemAt(0).uri)
            assertTrue(payload.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    private fun withSyntheticFile(directory: File, block: (File) -> Unit) {
        val createdDirectory = !directory.exists()
        check(directory.isDirectory || directory.mkdirs())
        val file = File.createTempFile("share-fixture-", ".png", directory)
        try {
            file.writeText("synthetic share fixture")
            block(file)
        } finally {
            file.delete()
            if (createdDirectory) directory.delete()
        }
    }
}
