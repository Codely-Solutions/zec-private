package co.electriccoin.zcash.ui.common.serialization.metadata

import co.electriccoin.zcash.ui.common.model.metadata.MetadataV3
import co.electriccoin.zcash.ui.common.serialization.METADATA_SERIALIZATION_V3
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.util.SecretBytes
import io.mockk.every
import io.mockk.mockk
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetadataEncryptorDiagnosticTest {
    @Test
    fun authenticatedButUnreadableMetadataKeepsPlaintextOutOfLogs() {
        val sentinel = "synthetic wallet note"
        val key = MetadataKey(listOf(SecretBytes.copyFrom(ByteArray(32), InsecureSecretKeyAccess.get())))
        val serializer = mockk<MetadataSerializer> {
            every { serialize(any(), any()) } answers {
                firstArg<OutputStream>().write("{\"version\":${METADATA_SERIALIZATION_V3},\"$sentinel".toByteArray())
            }
        }
        val encoded = ByteArrayOutputStream()
        val fixture = mockk<MetadataV3> { every { version } returns METADATA_SERIALIZATION_V3 }
        MetadataEncryptorImpl(serializer).encrypt(key, encoded, fixture)
        ShadowLog.clear()

        assertFailsWith<DecryptionException> {
            MetadataEncryptorImpl(MetadataSerializer()).decrypt(key, encoded.toByteArray().inputStream())
        }
        val logs = ShadowLog.getLogs()
        assertTrue(logs.any { "Failed to decrypt or decode metadata" in it.msg })
        assertTrue(logs.none { sentinel in it.msg || sentinel in it.throwable?.stackTraceToString().orEmpty() })
    }
}
