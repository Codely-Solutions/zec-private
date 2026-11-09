package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Local mainnet syntax/checksum checks. A valid result says nothing about account existence,
 * ownership, exchange support or token acceptance. Raw EVM/Solana addresses do not identify a chain.
 * Zcash unified/Sapling encodings additionally need SDK validation before use.
 *
 * Specifications: https://bips.dev/173/ and https://bips.dev/350/ (Wuille/Maxwell, BSD-2-Clause),
 * https://bips.dev/21/, https://eips.ethereum.org/EIPS/eip-55,
 * https://eips.ethereum.org/EIPS/eip-681,
 * https://github.com/bitcoincashorg/bitcoincash.org/blob/master/spec/cashaddr.md,
 * https://xrpl.org/docs/concepts/accounts/addresses,
 * https://developers.tron.network/docs/account, https://solana.com/docs/core/accounts,
 * https://nomicon.io/DataStructures/Account, https://docs.sui.io/concepts/sui-move-concepts/addresses.
 * Algorithms below are independent implementations of these formats; no signing/key handling.
 */
// Protocol constants and validation branches follow the wire formats directly.
@Suppress("TooManyFunctions")
object RecipientAddressPolicy {
    enum class Reason { INVALID_ADDRESS, UNSUPPORTED_NETWORK, UNSUPPORTED_URI, UNSUPPORTED_ROUTING }

    sealed interface Validation {
        data class Valid(val requiresZcashSdkValidation: Boolean = false) : Validation
        data class Invalid(val reason: Reason) : Validation
    }

    data class RecipientInput(
        val address: String,
        val network: String? = null,
        /** Native network ticker, or an explicit token contract for supported future URI formats. */
        val asset: String? = null,
        /** Decimal native-asset units, never base units. */
        val amount: BigDecimal? = null,
        val routing: Map<String, String> = emptyMap()
    )

    sealed interface ParseResult {
        data class Parsed(val input: RecipientInput) : ParseResult
        data class Invalid(val reason: Reason) : ParseResult
    }

    private val evmNetworks = setOf("arb", "avax", "base", "bsc", "eth", "pol")
    private val chainIds = mapOf("1" to "eth", "56" to "bsc", "137" to "pol",
        "8453" to "base", "42161" to "arb", "43114" to "avax")
    private val uriNetworks = mapOf(
        "bitcoin" to "btc", "litecoin" to "ltc", "bitcoincash" to "bch", "dash" to "dash", "zcash" to "zec"
    )
    private const val SUI_HEX_LENGTH = 64
    private const val MAX_INPUT_LENGTH = 2048
    private const val MAX_EVM_AMOUNT_LENGTH = 100
    private const val MAX_EVM_AMOUNT_PRECISION = 78
    private const val EVM_AMOUNT_BITS = 256
    private const val EVM_DECIMAL_PLACES = 18
    private const val MAX_NATIVE_AMOUNT_LENGTH = 32
    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val XRP58 = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"
    private const val BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    // Prefixes and payload lengths follow each network address wire format.
    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    fun validate(address: String, network: String): Validation {
        if (address.isEmpty() || address.length > 512) {
            return Validation.Invalid(Reason.INVALID_ADDRESS)
        }
        if (address.any { it.isWhitespace() || it.code < 33 }) {
            return Validation.Invalid(Reason.INVALID_ADDRESS)
        }
        val valid = when (network) {
            in evmNetworks -> evm(address)
            "btc" -> checked58(address, listOf(byteArrayOf(0), byteArrayOf(5)), 20) || witness(address, "bc")
            // M-prefixed P2SH is unambiguous. Legacy 3-prefixed Litecoin/BTC P2SH is shared.
            "ltc" -> checked58(address, listOf(byteArrayOf(48), byteArrayOf(50), byteArrayOf(5)), 20) ||
                witness(address, "ltc")
            "dash" -> checked58(address, listOf(byteArrayOf(76), byteArrayOf(16)), 20)
            "bch" -> cashAddress(address) || checked58(address, listOf(byteArrayOf(0), byteArrayOf(5)), 20)
            "tron" -> checked58(address, listOf(byteArrayOf(65)), 20)
            // Preserve an X-address intact: its tag is part of the checksummed payment destination.
            "xrp" -> checked58(address, listOf(byteArrayOf(0)), 20, XRP58) || xAddress(address)
            "sol" -> decode58(address)?.size == 32
            "sui" -> address.matches(Regex("(?:0[xX])?[0-9a-fA-F]{1,64}"))
            "near" -> address.length in 2..64 && address.matches(Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")) &&
                !address.endsWith(".testnet")
            "zec" -> zcash(address)
            else -> return Validation.Invalid(Reason.UNSUPPORTED_NETWORK)
        }
        return if (valid) Validation.Valid(network == "zec" && !address.startsWith("t"))
        else Validation.Invalid(Reason.INVALID_ADDRESS)
    }

    /** Canonical equivalent for a selected network; keep the original text in the editable form.
     * Sui's SDK accepts short hex via normalizeSuiAddress. Provider requests use its full 32 bytes.
     * https://github.com/MystenLabs/ts-sdks/blob/main/packages/sui/src/utils/sui-types.ts
     */
    fun normalize(address: String, network: String): String =
        if (network == "sui" && address.matches(Regex("(?:0[xX])?[0-9a-fA-F]{1,64}"))) {
            "0x" + address.removePrefix("0x").removePrefix("0X").lowercase(Locale.ROOT).padStart(SUI_HEX_LENGTH, '0')
        } else address

    /** Strict subset: simple BIP21-style/ZIP321 single-recipient native requests and EIP681 native transfers.
     * Unknown parameters, token calls, memos and destination tags fail closed; nothing is stripped.
     * Raw input remains byte-for-byte unchanged, including whitespace for subsequent validation.
     */
    // Keep the ordered raw-address and URI rejection gates visible.
    @Suppress("ReturnCount")
    fun parse(input: String): ParseResult {
        if (input.length > MAX_INPUT_LENGTH) return unsupported()
        // A prefixed cashaddr is an address, not a payment URI.
        if (input.startsWith("bitcoincash:", true) && !input.contains('?') && cashAddress(input)) {
            return ParseResult.Parsed(RecipientInput(input, "bch", "bch"))
        }
        if (!input.contains(':')) return ParseResult.Parsed(RecipientInput(input))
        return runCatching { parseUri(input) }.getOrElse { ParseResult.Invalid(Reason.UNSUPPORTED_URI) }
    }

    // Reject malformed structure and duplicate keys before scheme-specific parsing.
    @Suppress("ReturnCount")
    private fun parseUri(input: String): ParseResult {
        val uri = URI(input)
        if (uri.rawFragment != null || uri.rawSchemeSpecificPart.startsWith("//")) return unsupported()
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return unsupported()
        val body = uri.rawSchemeSpecificPart
        val target = body.substringBefore('?')
        val params = linkedMapOf<String, String>()
        val query = body.substringAfter('?', "")
        if (query.isNotEmpty()) {
            for (part in query.split('&')) {
                if (!part.contains('=')) return unsupported()
                val key = decode(part.substringBefore('='))
                val value = decode(part.substringAfter('='))
                if (params.put(key, value) != null) return unsupported()
            }
        }
        return if (scheme == "ethereum") parseEthereumUri(target, params) else parseNativeUri(scheme, target, params)
    }

    // Reject unsupported calls, chain IDs and amounts at their field boundaries.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun parseEthereumUri(target: String, params: Map<String, String>): ParseResult {
        if (params.keys.any { it != "value" } || target.contains('/')) return unsupported()
        val destination = target.removePrefix("pay-")
        if (destination.count { it == '@' } > 1) return unsupported()
        val explicitChain = destination.substringAfter('@', "")
        // ERC681 defaults to the current chain when absent; retain this ambiguity for the selected network.
        val network = if (explicitChain.isEmpty()) null else chainIds[explicitChain] ?: return unsupported()
        if (network != null && network !in evmNetworks) return unsupported()
        val address = destination.substringBefore('@')
        if (!evm(address)) return ParseResult.Invalid(Reason.INVALID_ADDRESS)
        val amount = params["value"]?.let {
            if (!it.matches(Regex("[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) || it.length > MAX_EVM_AMOUNT_LENGTH) {
                return unsupported()
            }
            val baseUnits = it.toBigDecimal()
            if (baseUnits.scale() !in -MAX_EVM_AMOUNT_PRECISION..MAX_EVM_AMOUNT_PRECISION ||
                baseUnits.scale() > 0 && baseUnits.stripTrailingZeros().scale() > 0 ||
                baseUnits.precision() > MAX_EVM_AMOUNT_PRECISION ||
                baseUnits.abs() >= BigDecimal(BigInteger.ONE.shiftLeft(EVM_AMOUNT_BITS))
            ) return unsupported()
            baseUnits.movePointLeft(EVM_DECIMAL_PLACES)
        }
        // Polygon's native asset differs; all other curated EVM chains use ETH/BNB/AVAX.
        val asset = when (network) { "pol" -> "pol"; "bsc" -> "bnb"; "avax" -> "avax"; else -> "eth" }
        return ParseResult.Parsed(RecipientInput(address, network, asset, amount))
    }

    // Keep native URI rejection gates beside the field each validates.
    // BCH legacy prefixes and payload lengths are wire-format constants.
    @Suppress("ReturnCount", "MagicNumber")
    private fun parseNativeUri(scheme: String, target: String, params: Map<String, String>): ParseResult {
        val network = uriNetworks[scheme] ?: return unsupported()
        if (params.keys.any { it !in setOf("amount", "label", "message") }) return unsupported()
        // Labels/messages have no payment effect, but retaining them prevents scanner data loss.
        val metadata = params.filterKeys { it == "label" || it == "message" }
        val decodedTarget = decode(target)
        val address = if (network == "bch" && !checked58(decodedTarget, listOf(byteArrayOf(0), byteArrayOf(5)), 20)) {
            val prefix = if (decodedTarget == decodedTarget.uppercase(Locale.ROOT)) "BITCOINCASH:" else "bitcoincash:"
            prefix + decodedTarget
        } else decodedTarget
        if (validate(address, network) !is Validation.Valid) return ParseResult.Invalid(Reason.INVALID_ADDRESS)
        val amount = params["amount"]?.let {
            if (!it.matches(Regex("[0-9]+(?:\\.[0-9]{1,8})?")) || it.length > MAX_NATIVE_AMOUNT_LENGTH) {
                return unsupported()
            }
            it.toBigDecimal()
        }
        return ParseResult.Parsed(RecipientInput(address, network, network, amount, metadata))
    }

    private fun unsupported() = ParseResult.Invalid(Reason.UNSUPPORTED_URI)
    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    private fun decode58(value: String, alphabet: String = BASE58): ByteArray? {
        if (value.isEmpty() || value.length > 128) return null
        var number = BigInteger.ZERO
        for (char in value) {
            val digit = alphabet.indexOf(char)
            if (digit < 0) return null
            number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val bytes = if (number == BigInteger.ZERO) byteArrayOf() else {
            number.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        }
        return ByteArray(value.takeWhile { it == alphabet[0] }.length) + bytes
    }

    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    private fun checked58(value: String, prefixes: List<ByteArray>, length: Int, alphabet: String = BASE58): Boolean {
        val bytes = decode58(value, alphabet) ?: return false
        return prefixes.any { prefix ->
            bytes.size == prefix.size + length + 4 && bytes.take(prefix.size).toByteArray().contentEquals(prefix) &&
                sha256(sha256(bytes.dropLast(4).toByteArray())).take(4).toByteArray()
                    .contentEquals(bytes.takeLast(4).toByteArray())
        }
    }

    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)

    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    private fun xAddress(value: String): Boolean {
        // XRPLF codec: mainnet prefix, account ID, tag-present flag, uint32 LE tag, four reserved zeros.
        // https://github.com/XRPLF/xrpl.js/blob/main/packages/ripple-address-codec/src/index.ts
        if (!checked58(value, listOf(byteArrayOf(0x05, 0x44)), 29, XRP58)) return false
        val bytes = decode58(value, XRP58) ?: return false
        val flag = bytes[22].toInt()
        if (flag !in 0..1 || bytes.slice(27..30).any { it != 0.toByte() }) return false
        return flag == 1 || bytes.slice(23..26).all { it == 0.toByte() }
    }

    private data class Bech(val hrp: String, val data: List<Int>, val checksum: Int)

    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
    private fun bech(value: String, maxLength: Int = 90): Bech? {
        if (value.length !in 8..maxLength || value.any { it.code !in 33..126 } ||
            value != value.lowercase(Locale.ROOT) && value != value.uppercase(Locale.ROOT)) return null
        val lower = value.lowercase(Locale.ROOT)
        val separator = lower.lastIndexOf('1')
        if (separator < 1 || separator + 7 > lower.length) return null
        val hrp = lower.substring(0, separator)
        val data = lower.substring(separator + 1).map { BECH32.indexOf(it) }
        if (data.any { it < 0 }) return null
        val expanded = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }
        var check = 1
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        for (v in expanded + data) {
            val top = check ushr 25
            check = ((check and 0x1ffffff) shl 5) xor v
            for (i in 0..4) if ((top ushr i) and 1 != 0) check = check xor generators[i]
        }
        return Bech(hrp, data.dropLast(6), check)
    }

    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    private fun convertBits(data: List<Int>, from: Int, to: Int): ByteArray? {
        var acc = 0
        var bits = 0
        val output = mutableListOf<Byte>()
        for (value in data) {
            acc = ((acc shl from) or value) and ((1 shl (from + to - 1)) - 1)
            bits += from
            while (bits >= to) { bits -= to; output += ((acc shr bits) and ((1 shl to) - 1)).toByte() }
        }
        if (bits >= from || ((acc shl (to - bits)) and ((1 shl to) - 1)) != 0) return null
        return output.toByteArray()
    }

    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    private fun witness(value: String, hrp: String): Boolean {
        val decoded = bech(value) ?: return false
        if (decoded.hrp != hrp || decoded.data.isEmpty()) return false
        val version = decoded.data.first()
        val program = convertBits(decoded.data.drop(1), 5, 8) ?: return false
        return version in 0..16 && program.size in 2..40 &&
            if (version == 0) decoded.checksum == 1 && program.size in setOf(20, 32)
            else decoded.checksum == 0x2bc830a3
    }

    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    private fun zcash(value: String): Boolean {
        val transparentPrefixes = listOf(byteArrayOf(0x1c, 0xb8.toByte()), byteArrayOf(0x1c, 0xbd.toByte()))
        if (checked58(value, transparentPrefixes, 20)) return true
        val decoded = bech(value, 512) ?: return false
        val bytes = convertBits(decoded.data, 5, 8) ?: return false
        return when (decoded.hrp) {
            "zs" -> decoded.checksum == 1 && bytes.size == 43
            // ZIP316 F4Jumble, receiver rules and embedded HRP padding are checked by the SDK.
            "u" -> decoded.checksum == 0x2bc830a3 && bytes.size in 48..300
            else -> false
        }
    }

    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    private fun cashAddress(value: String): Boolean {
        if (value != value.lowercase(Locale.ROOT) && value != value.uppercase(Locale.ROOT)) return false
        val lower = value.lowercase(Locale.ROOT)
        if (lower.count { it == ':' } > 1) return false
        val prefix = if (':' in lower) lower.substringBefore(':') else "bitcoincash"
        if (prefix != "bitcoincash") return false
        val data = lower.substringAfter(':', lower).map { BECH32.indexOf(it) }
        if (data.size < 8 || data.any { it < 0 }) return false
        var check = 1L
        val generators = longArrayOf(0x98f2bc8e61, 0x79b76d99e2, 0xf33e5fb3c4, 0xae2eabe2a8, 0x1e4f43e470)
        for (v in prefix.map { it.code and 31 } + listOf(0) + data) {
            val top = check ushr 35
            check = ((check and 0x07ffffffff) shl 5) xor v.toLong()
            for (i in 0..4) if ((top ushr i) and 1L != 0L) check = check xor generators[i]
        }
        if (check != 1L) return false
        val payload = convertBits(data.dropLast(8), 5, 8) ?: return false
        if (payload.isEmpty()) return false
        val version = payload[0].toInt() and 255
        // Cashaddr size bits and currently defined types, including token-aware P2PKH/P2SH.
        // Token awareness is a receiver capability, not a request to substitute a token for native BCH.
        // https://github.com/bitjson/cashtokens/blob/master/readme.md#cashaddress-token-support
        val sizes = intArrayOf(20, 24, 28, 32, 40, 48, 56, 64)
        return version and 0x80 == 0 && (version ushr 3) in 0..3 &&
            payload.size == sizes[version and 7] + 1
    }

    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    private fun evm(value: String): Boolean {
        if (!value.matches(Regex("0x[0-9a-fA-F]{40}"))) return false
        val hex = value.drop(2)
        if (hex == hex.lowercase(Locale.ROOT) || hex == hex.uppercase(Locale.ROOT)) return true
        val hash = keccak256(hex.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII))
        return hex.indices.all { i -> !hex[i].isLetter() || hex[i].isUpperCase() ==
            (((hash[i / 2].toInt() ushr (if (i % 2 == 0) 4 else 0)) and 15) >= 8) }
    }

    /** Keccak-f[1600], legacy Keccak-256 domain 0x01 (EIP55; distinct from SHA3-256). */
    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    private fun keccak256(input: ByteArray): ByteArray {
        val state = LongArray(25)
        val block = ByteArray(136)
        input.copyInto(block)
        block[input.size] = 1
        block[135] = 0x80.toByte()
        for (i in block.indices) state[i / 8] = state[i / 8] xor ((block[i].toLong() and 255) shl ((i % 8) * 8))
        val rotations = intArrayOf(
            0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14
        )
        val constants = arrayOf("0000000000000001", "0000000000008082", "800000000000808a", "8000000080008000",
            "000000000000808b", "0000000080000001", "8000000080008081", "8000000000008009",
            "000000000000008a", "0000000000000088", "0000000080008009", "000000008000000a",
            "000000008000808b", "800000000000008b", "8000000000008089", "8000000000008003",
            "8000000000008002", "8000000000000080", "000000000000800a", "800000008000000a",
            "8000000080008081", "8000000000008080", "0000000080000001", "8000000080008008")
        for (constant in constants) {
            val c = LongArray(5) { x ->
                state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
            }
            val d = LongArray(5) { x -> c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1) }
            for (i in state.indices) state[i] = state[i] xor d[i % 5]
            val b = LongArray(25)
            for (x in 0..4) for (y in 0..4) {
                b[y + 5 * ((2 * x + 3 * y) % 5)] =
                    java.lang.Long.rotateLeft(state[x + 5 * y], rotations[x + 5 * y])
            }
            for (x in 0..4) for (y in 0..4) {
                state[x + 5 * y] = b[x + 5 * y] xor
                    (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            }
            state[0] = state[0] xor java.lang.Long.parseUnsignedLong(constant, 16)
        }
        return ByteArray(32) { (state[it / 8] ushr ((it % 8) * 8)).toByte() }
    }
}
