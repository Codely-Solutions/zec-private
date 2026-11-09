package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@Suppress("MagicNumber", "MaxLineLength")
class RecipientAddressPolicyTest {
    private val policy = RecipientAddressPolicy

    @Test
    fun eip55VectorsAndSharedEvmNetworks() {
        // ERC55 official test cases (https://eips.ethereum.org/EIPS/eip-55).
        val vectors = listOf(
            "0x52908400098527886E0F7030069857D2E4169EE7",
            "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
            "0xde709f2102306220921060314715629080e2fb77",
            "0x27b1fdb04752bbc536007a920d24acb045561c26",
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
        )
        for (network in listOf("arb", "avax", "base", "bsc", "eth", "pol")) {
            for (address in vectors) valid(address, network)
            invalid("0x5aEDA56215b167893e80B4fE645BA6d5Bab767DE", network)
            invalid(vectors.first().dropLast(1), network)
        }
    }

    @Test
    fun bitcoinAndCashaddrPublishedVectors() {
        // BIP173/BIP350 official Segwit vectors; cashaddr specification conversion example.
        valid("1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu", "btc")
        valid("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "btc")
        valid("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", "btc")
        valid("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0", "btc")
        invalid("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd", "btc")
        invalid("bc1Qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "btc")
        invalid("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080", "btc")
        valid("bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a", "bch")
        valid("qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a", "bch")
        invalid("bchtest:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a", "bch")
        invalid("bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6q", "bch")
    }

    @Test
    fun versionBytesAndChecksumsAreEnforced() {
        // Deterministic synthetic public payloads. Version bytes from each maintained chainparams.cpp;
        // these fixtures represent encoding only, with no associated key or funding.
        val versions = listOf("btc" to byteArrayOf(0), "ltc" to byteArrayOf(48),
            "dash" to byteArrayOf(76), "tron" to byteArrayOf(65), "zec" to byteArrayOf(0x1c, 0xb8.toByte()))
        for ((network, prefix) in versions) {
            val address = encodeCheck(prefix + ByteArray(20) { 7 })
            valid(address, network)
            invalid(address.dropLast(1) + if (address.last() == '1') "2" else "1", network)
            if (network != "btc") invalid(address, "btc")
        }
        invalid(encodeCheck(byteArrayOf(111) + ByteArray(20)), "btc")
        invalid(encodeCheck(byteArrayOf(0x1d, 0x25) + ByteArray(20)), "zec")
        valid("rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh", "xrp") // XRPL documentation example.
        invalid("rHb9CJAWyB4rj91VRWn96DkukG4bwdtyT1", "xrp")
    }

    @Test
    fun fixedWidthAndAccountNameFormats() {
        valid("11111111111111111111111111111111", "sol") // Solana system program, 32 zero bytes.
        invalid("1111111111111111111111111111111", "sol")
        valid("0x" + "a".repeat(64), "sui")
        valid("0x2", "sui")
        valid("A".repeat(63), "sui")
        assertEquals("0x" + "0".repeat(63) + "2", policy.normalize("0x2", "sui"))
        assertEquals("0x" + "0" + "a".repeat(63), policy.normalize("A".repeat(63), "sui"))
        invalid("0x" + "a".repeat(65), "sui")
        invalid("0x", "sui")
        invalid("0x0x2", "sui")
        valid("alice.near", "near")
        valid("a".repeat(64), "near")
        for (address in listOf("Alice.near", "alice..near", "alice.testnet", "-alice", "a")) invalid(address, "near")
        invalid(" alice.near", "near")
        invalid("alice.near\n", "near")
    }

    @Test
    fun allCashaddrHashSizesAndTokenAwareDestinationsArePreserved() {
        // Official cashaddr specification's larger mainnet vectors, in size order 20..64 bytes.
        val addresses = listOf(
            "bitcoincash:qr6m7j9njldwwzlg9v7v53unlr4jkmx6eylep8ekg2",
            "bitcoincash:q9adhakpwzztepkpwp5z0dq62m6u5v5xtyj7j3h2ws4mr9g0",
            "bitcoincash:qgagf7w02x4wnz3mkwnchut2vxphjzccwxgjvvjmlsxqwkcw59jxxuz",
            "bitcoincash:qvch8mmxy0rtfrlarg7ucrxxfzds5pamg73h7370aa87d80gyhqxq5nlegake",
            "bitcoincash:qnq8zwpj8cq05n7pytfmskuk9r4gzzel8qtsvwz79zdskftrzxtar994cgutavfklv39gr3uvz",
            "bitcoincash:qh3krj5607v3qlqh5c3wq3lrw3wnuxw0sp8dv0zugrrt5a3kj6ucysfz8kxwv2k53krr7n933jfsunqex2w82sl",
            "bitcoincash:qmvl5lzvdm6km38lgga64ek5jhdl7e3aqd9895wu04fvhlnare5937w4ywkq57juxsrh" +
                "vw8ym5d8qx7sz7zz0zvcypqscw8jd03f",
            "bitcoincash:qlg0x333p4238k0qrc5ej7rzfw5g8e4a4r6vvzyrcy8j3s5k0en7calvclhw46hudk5f" +
                "lttj6ydvjc0pv3nchp52amk97tqa5zygg96mtky5sv5w",
            // CashTokens specification: token-aware P2PKH and 32-byte token-aware P2SH.
            "bitcoincash:zr7fzmep8g7h7ymfxy74lgc0v950j3r295z4y4gq0v",
            "bitcoincash:rvqqqqqqqqqqqqqqqqqqqqqqzg69v7ysqqqqqqqqqqqqqqqqqqqqqn9alsp2y"
        )
        for (address in addresses) {
            valid(address, "bch")
            assertEquals(address,
                assertIs<RecipientAddressPolicy.ParseResult.Parsed>(policy.parse(address)).input.address)
            invalid(address.dropLast(1) + if (address.last() == 'q') "p" else "q", "bch")
        }
        // Published checksum-only vector deliberately has an invalid payload.
        invalid("bitcoincash:qpzry9x8gf2tvdw0s3jn54khce6mua7lcw20ayyn", "bch")
        val uppercase = "BITCOINCASH:QPM2QSZNHKS23Z7629MMS6S4CWEF74VCWVY22GDX6A"
        assertIs<RecipientAddressPolicy.ParseResult.Parsed>(policy.parse("$uppercase?amount=1"))
        assertIs<RecipientAddressPolicy.ParseResult.Parsed>(
            policy.parse("bitcoincash:1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu?amount=1")
        )
    }

    @Test
    fun xrpXAddressRetainsEncodedTagAndRejectsTestnetOrInvalidChecksum() {
        // XRPLF ripple-address-codec official vectors: no tag, tag 1, tag uint32 max.
        val mainnet = listOf(
            "X7AcgcsBL6XDcUb289X4mJ8djcdyKaB5hJDWMArnXr61cqZ",
            "X7AcgcsBL6XDcUb289X4mJ8djcdyKaGZMhc9YTE92ehJ2Fu",
            "XVLhHMPHU98es4dbozjVtdWzVrDjtV18pX8yuPT7y4xaEHi"
        )
        for (address in mainnet) {
            valid(address, "xrp")
            assertEquals(address,
                assertIs<RecipientAddressPolicy.ParseResult.Parsed>(policy.parse(address)).input.address)
        }
        invalid("T719a5UwUCnEs54UsxG9CJYYDhwmFCvbJNZbi37gBGkRkbE", "xrp")
        invalid(mainnet.first().dropLast(1) + "r", "xrp")
        invalid("r9cZA1mLK5R5Am25ArfXFmqgNwjZgnfk59:1", "xrp")
        val xrpAlphabet = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"
        for (routing in listOf(
            byteArrayOf(2) + ByteArray(8), // Undefined tag flag, even with a valid checksum.
            byteArrayOf(0, 1) + ByteArray(7), // Tag bytes with the no-tag flag.
            byteArrayOf(1) + ByteArray(7) + byteArrayOf(1) // Nonzero reserved byte.
        )) {
            invalid(encodeCheck(byteArrayOf(5, 68) + ByteArray(20) + routing, xrpAlphabet), "xrp")
        }
    }

    @Test
    fun unifiedZcashChecksumStillRequiresSdkReceiverValidation() {
        // https://github.com/zcash/zcash-test-vectors/blob/master/test-vectors/json/unified_address.json row 0.
        // Public address only; no private material copied.
        val address = "u1l8xunezsvhq8fgzfl7404m450nwnd76zshscn6nfys7vyz2ywyh4cc5daaq0c7q2su5lqfh23sp7fk" +
            "f3kt27ve5948mzpfdvckzaect2jtte308mkwlycj2u0eac077wu70vqcetkxf"
        assertEquals(true, assertIs<RecipientAddressPolicy.Validation.Valid>(policy.validate(address,
            "zec")).requiresZcashSdkValidation)
        invalid(address.dropLast(1) + "q", "zec")
        invalid(address.replaceFirst("u1", "utest1"), "zec")
        val parsed = assertIs<RecipientAddressPolicy.ParseResult.Parsed>(
            policy.parse("zcash:$address?amount=1.25")
        ).input
        assertEquals("zec", parsed.network)
        assertEquals("zec", parsed.asset)
        assertEquals(BigDecimal("1.25"), parsed.amount)
        for (query in listOf("memo=YWJj", "address.1=$address", "amount.1=2", "req-unknown=1")) {
            assertIs<RecipientAddressPolicy.ParseResult.Invalid>(policy.parse("zcash:$address?$query"))
        }
    }

    @Test
    fun bip21RetainsNetworkAmountAndMetadata() {
        val parsed = assertIs<RecipientAddressPolicy.ParseResult.Parsed>(
            policy.parse("bitcoin:1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu?amount=0.25&label=Example%20recipient&message=a+b")
        ).input
        assertEquals("btc", parsed.network)
        assertEquals("btc", parsed.asset)
        assertEquals(BigDecimal("0.25"), parsed.amount)
        assertEquals(mapOf("label" to "Example recipient", "message" to "a+b"), parsed.routing)
        for (field in listOf("req-extra=1", "dt=1", "amount=1&amount=2", "amount=-1", "amount=1e4",
            "amount=0.123456789", "lightning=anything")) {
            assertIs<RecipientAddressPolicy.ParseResult.Invalid>(
                policy.parse("bitcoin:1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu?$field")
            )
        }
    }

    @Test
    fun eip681PreservesChainAndConvertsWei() {
        val address = "0xde709f2102306220921060314715629080e2fb77"
        val parsed = assertIs<RecipientAddressPolicy.ParseResult.Parsed>(
            policy.parse("ethereum:$address@8453?value=2.014e18")
        ).input
        assertEquals("base", parsed.network)
        assertEquals("eth", parsed.asset)
        assertEquals(0, BigDecimal("2.014").compareTo(parsed.amount))
        for (suffix in listOf("@11155111", "@1/transfer?address=$address&uint256=1", "@1?value=1.2",
            "@1?value=1e99999999", "@1?gas=21000", "@1?value=1&value=2")) {
            assertIs<RecipientAddressPolicy.ParseResult.Invalid>(policy.parse("ethereum:$address$suffix"))
        }
    }

    @Test
    fun unknownProtocolsAndRoutingFailClosedAndRawInputIsPreserved() {
        for (input in listOf("solana:11111111111111111111111111111111?reference=x", "https://example.com/pay",
            "xrpl:rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh?dt=1", "bitcoin://abc", "bitcoin:abc#ignored")) {
            assertIs<RecipientAddressPolicy.ParseResult.Invalid>(policy.parse(input))
        }
        assertEquals(" alice.near ",
            assertIs<RecipientAddressPolicy.ParseResult.Parsed>(policy.parse(" alice.near ")).input.address)
        assertEquals("bch",
            assertIs<RecipientAddressPolicy.ParseResult.Parsed>(
                policy.parse("bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a")
            ).input.network
        )
    }

    private fun valid(address: String, network: String) {
        assertIs<RecipientAddressPolicy.Validation.Valid>(policy.validate(address, network), "$network: $address")
    }

    private fun invalid(address: String, network: String) {
        assertIs<RecipientAddressPolicy.Validation.Invalid>(policy.validate(address, network), "$network: $address")
    }

    private fun encodeCheck(
        payload: ByteArray,
        alphabet: String = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = payload + digest.digest(digest.digest(payload)).take(4).toByteArray()
        var number = BigInteger(1, bytes)
        var result = ""
        while (number > BigInteger.ZERO) {
            val division = number.divideAndRemainder(BigInteger.valueOf(58))
            result = alphabet[division[1].toInt()] + result
            number = division[0]
        }
        return alphabet.first().toString().repeat(bytes.takeWhile { it == 0.toByte() }.size) + result
    }
}
