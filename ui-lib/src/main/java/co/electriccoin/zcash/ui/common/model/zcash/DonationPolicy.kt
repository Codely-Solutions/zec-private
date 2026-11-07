package co.electriccoin.zcash.ui.common.model.zcash

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.model.NetworkDimension
import co.electriccoin.zcash.ui.common.model.VersionInfo
import java.math.BigDecimal
import java.math.RoundingMode
import org.zecdev.zip321.ZIP321
import org.zecdev.zip321.parser.ParserContext

/** Public receiving address supplied by the project owner; no spending key is stored here. */
@Suppress("MagicNumber")
object DonationPolicy {
    const val RECIPIENT =
        "u16hcm5ln6c9yvj7snd3k2m9s5zlg2hntnqupzpl7r7xxqf23pf9dze7ja6782kjc454pu2kamk0xawul5zv9arugr797xfse385cgjmar"
    const val DOCUMENT = "https://github.com/Sammy-Lastre/zec-private/blob/main/docs/DONATIONS.md"
    const val COOLDOWN_MILLIS = 90L * 24 * 60 * 60 * 1000

    @Volatile private var isolatedTestnetRecipient: String? = null

    fun recipient(): String? =
        if (VersionInfo.NETWORK_DIMENSION == NetworkDimension.MAINNET) RECIPIENT else isolatedTestnetRecipient

    /** Only the isolated testnet debug harness can configure a valueless test recipient. */
    fun setIsolatedTestnetRecipient(address: String) {
        check(BuildConfig.DEBUG && VersionInfo.NETWORK_DIMENSION == NetworkDimension.TESTNET)
        isolatedTestnetRecipient = address
    }

    fun suggestedZatoshi(basisZec: BigDecimal, percent: BigDecimal): Long {
        require(basisZec.signum() >= 0 && percent >= BigDecimal.ZERO && percent <= BigDecimal(100))
        return basisZec.multiply(percent).movePointRight(6).setScale(0, RoundingMode.DOWN).longValueExact()
    }

    /** Inspect the SDK's actual proposal, not a balance estimate or the address prefix. */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    fun requireShielded(proposal: Proposal, send: ZecSend) {
        val decoded = ProposalOuterClass.Proposal.parseFrom(proposal.toByteArray())
        require(decoded.stepsCount == 1) { "Unsupported donation transaction shape" }
        val step = decoded.stepsList.single()
        val parsed = ZIP321.request(
            uriString = step.transactionRequest,
            context = when (VersionInfo.NETWORK_DIMENSION) {
                NetworkDimension.MAINNET -> ParserContext.MAINNET
                NetworkDimension.TESTNET -> ParserContext.TESTNET
            },
            validatingRecipients = null
        )
        require(parsed is ZIP321.ParserResult.Request && parsed.paymentRequest.payments.size == 1) {
            "Donation requires one reviewed payment"
        }
        val payment = parsed.paymentRequest.payments.single()
        require(payment.recipientAddress.value == send.destination.address &&
            payment.nonNegativeAmount?.toZecValueString()?.toBigDecimal()?.movePointRight(8)?.longValueExact() ==
            send.amount.value && isEmptyMemo(payment.memo?.data)) { "Donation payment differs from review" }
        require(!step.isShielding && step.inputsCount > 0) { "Donation requires shielded funds" }
        require(step.inputsList.all { it.hasReceivedOutput() && isShielded(it.receivedOutput.valuePool) }) {
            "Donation requires shielded inputs"
        }
        require(step.paymentOutputPoolsCount == 1 && step.paymentOutputPoolsList.single().paymentIndex == 0 &&
            isShielded(step.paymentOutputPoolsList.single().valuePool)) {
            "Donation requires a shielded recipient"
        }
        require(step.balance.proposedChangeList.all { !it.isEphemeral && isShielded(it.valuePool) }) {
            "Donation requires shielded change"
        }
        val spent = step.inputsList.fold(0L) { total, input -> Math.addExact(total, input.receivedOutput.value) }
        val change = step.balance.proposedChangeList.fold(0L) { total, output -> Math.addExact(total, output.value) }
        require(Math.subtractExact(Math.subtractExact(spent, change), step.balance.feeRequired) == send.amount.value) {
            "Donation amount differs from proposal"
        }
        require(send.memo.value.isEmpty()) { "Donations do not include identifying memos" }
    }

    // ZIP321 may preserve the protocol's empty-memo marker and its zero padding.
    private fun isEmptyMemo(data: ByteArray?): Boolean =
        data == null || (data.size <= 512 &&
            (data.all { it == 0.toByte() } ||
                (data.firstOrNull() == 0xf6.toByte() && data.drop(1).all { it == 0.toByte() })))

    private fun isShielded(pool: ProposalOuterClass.ValuePool): Boolean =
        pool == ProposalOuterClass.ValuePool.Sapling ||
            pool == ProposalOuterClass.ValuePool.Orchard || pool == ProposalOuterClass.ValuePool.Ironwood
}
