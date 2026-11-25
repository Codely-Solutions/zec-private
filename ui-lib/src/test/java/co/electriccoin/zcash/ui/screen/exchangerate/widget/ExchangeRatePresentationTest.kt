package co.electriccoin.zcash.ui.screen.exchangerate.widget

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExchangeRatePresentationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun refreshingExpiredRateHidesFiatUntilBothDisplaysHaveAValidConversion() {
        val now = Instant.parse("2026-11-25T00:00:00Z")
        val clock = object : Clock { override fun now() = now }
        val fresh = ExchangeRateState.Data(
            isLoading = false,
            currencyConversion = FiatCurrencyConversion(now, 150.0),
            clock = clock,
            onRefresh = {},
        )
        val state = mutableStateOf(fresh)
        val amount = Zatoshi(100_000_000)
        compose.setContent {
            ZcashTheme {
                Column {
                    StyledExchangeBalance(amount, state.value)
                    StyledExchangeLabel(amount, state.value)
                }
            }
        }
        compose.onAllNodesWithText("150", substring = true).assertCountEquals(2)
        capture("fresh-exchange-rate")

        compose.runOnIdle {
            state.value = fresh.copy(
                isLoading = true,
                currencyConversion = FiatCurrencyConversion(now - 16.minutes, 150.0),
            )
        }
        compose.onAllNodesWithText("150", substring = true).assertCountEquals(0)
        capture("expired-exchange-rate-refresh")

        compose.runOnIdle {
            state.value = fresh.copy(currencyConversion = FiatCurrencyConversion(now, 160.0))
        }
        compose.onAllNodesWithText("160", substring = true).assertCountEquals(2)
        capture("recovered-exchange-rate")
    }

    private fun capture(name: String) {
        val directory = System.getProperty("private.review.captureDir")?.let(::File) ?: return
        directory.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
