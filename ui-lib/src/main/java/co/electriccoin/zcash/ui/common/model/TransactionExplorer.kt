package co.electriccoin.zcash.ui.common.model

object TransactionExplorer {
    private val hex = Regex("[0-9a-fA-F]{64}")
    private val evm = Regex("0x[0-9a-fA-F]{64}")
    private val sol = Regex("[1-9A-HJ-NP-Za-km-z]{87,88}")

    private data class Explorer(val prefix: String, val idPattern: Regex, val testnetPrefix: String? = null)

    private val explorers = mapOf(
        "zec" to Explorer("https://blockbook.zec.zelcore.io/tx/", hex, "https://testnet.zecblock.com/tx/"),
        "btc" to Explorer("https://mempool.space/tx/", hex),
        "sol" to Explorer("https://solscan.io/tx/", sol),
        "eth" to Explorer("https://etherscan.io/tx/", evm),
        "arb" to Explorer("https://arbiscan.io/tx/", evm),
        "base" to Explorer("https://basescan.org/tx/", evm),
        "bsc" to Explorer("https://bscscan.com/tx/", evm),
        "op" to Explorer("https://optimistic.etherscan.io/tx/", evm),
        "pol" to Explorer("https://polygonscan.com/tx/", evm),
    )

    fun url(chain: String, id: String, testnet: Boolean = false): String? {
        val explorer = explorers[chain.lowercase()] ?: return null
        val prefix = if (testnet) explorer.testnetPrefix else explorer.prefix
        return if (prefix != null && explorer.idPattern.matches(id)) prefix + id else null
    }
}

val SwapQuoteStatus.positiveRefund get() = refundedFormatted?.takeIf { it.signum() > 0 }
