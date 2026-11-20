package co.electriccoin.zcash.ui.common.model

import java.math.BigInteger
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwapCatalogRecipientTest {
    @Test
    fun everySelectedAssetUsesItsNetworkWithoutInferringATokenFromRawSyntax() {
        for (asset in SwapCatalogTestFixture.assets) {
            val address = address(asset.chainTicker)
            val result = validateSwapRecipient(address, asset.chainTicker, asset.tokenTicker)

            assertTrue(result.isValid, asset.assetId)
            assertEquals(
                RecipientAddressPolicy.normalize(address, asset.chainTicker), result.input?.address, asset.assetId
            )
            assertNull(result.input?.network, asset.assetId)
            assertNull(result.input?.asset, asset.assetId)
        }
    }

    @Test
    fun contactNetworkMismatchBlocksEveryAssetUntilCorrected() {
        for (asset in SwapCatalogTestFixture.assets) {
            val address = address(asset.chainTicker)
            val otherNetwork = if (asset.chainTicker == "eth") "base" else "eth"
            val invalid = validateSwapRecipient(address, asset.chainTicker, asset.tokenTicker, otherNetwork)
            val corrected = validateSwapRecipient(address, asset.chainTicker, asset.tokenTicker, asset.chainTicker)

            assertFalse(invalid.isValid, asset.assetId)
            assertEquals(SwapRecipientError.NETWORK, invalid.error, asset.assetId)
            assertTrue(corrected.isValid, asset.assetId)
            assertEquals(
                RecipientAddressPolicy.normalize(address, asset.chainTicker), corrected.input?.address, asset.assetId
            )
        }
    }

    @Test
    fun invalidRawAddressBlocksEveryCatalogCombination() {
        for (asset in SwapCatalogTestFixture.assets) {
            val result = validateSwapRecipient("!invalid-address", asset.chainTicker, asset.tokenTicker)
            assertFalse(result.isValid, asset.assetId)
            assertEquals(SwapRecipientError.ADDRESS, result.error, asset.assetId)
        }
    }

    @Test
    fun explicitEthereumQrNetworkCannotBeReinterpretedForAnotherSelectedNetwork() {
        val uri = "ethereum:$EVM_ADDRESS@1"
        for (asset in SwapCatalogTestFixture.assets.filter { it.chainTicker != "eth" }) {
            val result = validateSwapRecipient(uri, asset.chainTicker, asset.tokenTicker)
            assertFalse(result.isValid, asset.assetId)
            assertEquals(SwapRecipientError.NETWORK, result.error, asset.assetId)
        }
    }

    // Reuse the published vectors and synthetic, keyless payloads in RecipientAddressPolicyTest.
    private fun address(network: String): String = when (network) {
        "arb", "avax", "base", "bsc", "eth", "pol" -> EVM_ADDRESS
        "btc" -> "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        "bch" -> "qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a"
        "sol" -> "11111111111111111111111111111111"
        "sui" -> "0x2"
        "near" -> "alice.near"
        "xrp" -> "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh"
        "ltc", "dash", "tron", "zec" -> encodedAddress(network)
        else -> error("Add a public recipient-format fixture for $network")
    }

    @Suppress("MagicNumber")
    private fun encodedAddress(network: String): String {
        val prefix = when (network) {
            "ltc" -> byteArrayOf(48)
            "dash" -> byteArrayOf(76)
            "tron" -> byteArrayOf(65)
            "zec" -> byteArrayOf(0x1c, 0xb8.toByte())
            else -> error(network)
        }
        val payload = prefix + ByteArray(20) { 7 }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = payload + digest.digest(digest.digest(payload)).take(4).toByteArray()
        var number = BigInteger(1, bytes)
        val result = StringBuilder()
        while (number > BigInteger.ZERO) {
            val division = number.divideAndRemainder(BigInteger.valueOf(58))
            result.append(BASE58[division[1].toInt()])
            number = division[0]
        }
        return result.reverse().toString()
    }

    private companion object {
        const val EVM_ADDRESS = "0x52908400098527886E0F7030069857D2E4169EE7"
        const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    }
}
