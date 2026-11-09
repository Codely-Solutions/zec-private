package co.electriccoin.zcash.ui.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransactionExplorerTest {
    private val hash = "ab".repeat(32)

    @Test
    fun zcashNetworkSelectsSeparateTrustedHosts() {
        assertEquals("https://blockbook.zec.zelcore.io/tx/$hash", TransactionExplorer.url("zec", hash))
        assertEquals("https://testnet.zecblock.com/tx/$hash", TransactionExplorer.url("zec", hash, true))
    }

    @Test
    fun chainIdentityControlsDestinationRatherThanTokenTicker() {
        assertEquals("https://basescan.org/tx/0x$hash", TransactionExplorer.url("base", "0x$hash"))
        assertNull(TransactionExplorer.url("zec", "0x$hash"))
        assertNull(TransactionExplorer.url("unknown", hash))
        assertNull(TransactionExplorer.url("eth", "0x$hash", true))
    }

    @Test
    fun identifiersCannotSupplyUrlOrChangeItsPath() {
        listOf("", "https://example.com", "$hash/other", "$hash?x=y", " $hash", "$hash#fragment").forEach {
            assertNull(TransactionExplorer.url("zec", it))
            assertNull(TransactionExplorer.url("eth", it))
        }
    }

    @Test
    fun solanaAcceptsSignaturesAndRejectsHexAndInvalidBase58() {
        val signature = "2".repeat(88)
        assertEquals("https://solscan.io/tx/$signature", TransactionExplorer.url("sol", signature))
        assertNull(TransactionExplorer.url("sol", hash))
        assertNull(TransactionExplorer.url("sol", "0".repeat(88)))
    }
}
