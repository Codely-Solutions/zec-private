package co.electriccoin.zcash.ui.design.util

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class ZatoshiDisplayPrecisionTest {
    @Test
    fun nonzeroSmallBalancesRetainAllEightZecDecimals() {
        val formatter = zatoshiFormatter()
        listOf("0.00000001", "0.0000001", "0.00000999").forEach { amount ->
            assertEquals(amount, formatter.format(BigDecimal(amount)))
        }
    }
}
