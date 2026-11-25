package co.electriccoin.zcash.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.QrCodeDefaults
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrCodeFullscreenLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Suppress("DEPRECATION")
    fun dismissingFullscreenShowsTheLatestInlineQrData() {
        val firstReceiver = "synthetic-receive-first"
        val rotatedReceiver = "synthetic-receive-rotated"
        val data = mutableStateOf(firstReceiver)
        compose.setContent {
            ZcashTheme {
                ZashiQr(
                    state = QrState(data.value),
                    modifier = Modifier.testTag(INLINE_QR),
                    qrSize = 180.dp,
                    colors = QrCodeDefaults.colors(Color.White, Color.Black),
                )
            }
        }
        assertEquals(firstReceiver, inlineContents())
        compose.onNodeWithTag(INLINE_QR).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNode(isDialog()).assertExists()

        compose.runOnIdle { data.value = rotatedReceiver }
        compose.runOnIdle { requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed() }

        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(rotatedReceiver, inlineContents())
    }

    private fun inlineContents(): String {
        val bitmap = compose.onNodeWithTag(INLINE_QR).captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private companion object {
        const val INLINE_QR = "inline-qr"
    }
}
