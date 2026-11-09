package co.electriccoin.zcash.ui.common.model

object TransactionExplorer {
    private val hex = Regex("[0-9a-fA-F]{64}")
    private val evm = Regex("0x[0-9a-fA-F]{64}")
    private val sol = Regex("[1-9A-HJ-NP-Za-km-z]{87,88}")

    fun url(chain: String, id: String, testnet: Boolean = false): String? {
        val prefix = when (chain.lowercase()) {
            "zec" -> {
                if (!hex.matches(id)) return null
                if (testnet) "https://testnet.zecblock.com/tx/"
                else "https://blockbook.zec.zelcore.io/tx/"
            }
            "btc" -> {
                if (testnet || !hex.matches(id)) return null
                "https://mempool.space/tx/"
            }
            "sol" -> {
                if (testnet || !sol.matches(id)) return null
                "https://solscan.io/tx/"
            }
            else -> {
                if (testnet || !evm.matches(id)) return null
                when (chain.lowercase()) {
                    "eth" -> "https://etherscan.io/tx/"
                    "arb" -> "https://arbiscan.io/tx/"
                    "base" -> "https://basescan.org/tx/"
                    "bsc" -> "https://bscscan.com/tx/"
                    "op" -> "https://optimistic.etherscan.io/tx/"
                    "pol" -> "https://polygonscan.com/tx/"
                    else -> return null
                }
            }
        }
        return prefix + id
    }
}

val SwapQuoteStatus.positiveRefund get() = refundedFormatted?.takeIf { it.signum() > 0 }
