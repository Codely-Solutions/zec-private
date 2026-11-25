package co.electriccoin.zcash.ui.common.datasource

import android.app.Application
import cash.z.ecc.android.sdk.GiftCardRedeemer
import cash.z.ecc.android.sdk.GiftCardRedeemers
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.GiftCardLinkError
import cash.z.ecc.android.sdk.model.MemoContent
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.GIFT_CARD_MEMO_SEPARATOR
import co.electriccoin.zcash.ui.common.model.GiftCardException
import co.electriccoin.zcash.ui.common.model.GiftCardHandle
import co.electriccoin.zcash.ui.common.model.GiftCardOrigin
import co.electriccoin.zcash.ui.common.model.GiftCardRedemption
import co.electriccoin.zcash.ui.common.model.GiftCardStatus
import co.electriccoin.zcash.ui.common.model.GiftCardSummary
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import cash.z.ecc.android.sdk.exception.GiftCardException as SdkGiftCardException
import cash.z.ecc.android.sdk.model.GiftCardOrigin as SdkGiftCardOrigin

/**
 * The app's only boundary to the SDK's gift card redemption. It holds the parsed cards and their redeemers, and
 * nothing else: the redemption flow itself, and when a card is closed, are decided by
 * [co.electriccoin.zcash.ui.common.repository.GiftCardRepository].
 *
 * Implementations must never log, persist or put into exception messages the link passed to [parse], nor any
 * secret derived from it.
 */
interface GiftCardDataSource {
    /**
     * Parses [link] and keeps the card in memory until [close].
     *
     * @throws GiftCardException.InvalidLink when the link cannot be read
     * @throws GiftCardException.WrongNetwork when the card is for another network than the wallet's
     * @throws GiftCardException.NotAvailable when this build cannot redeem gift cards
     */
    suspend fun parse(link: String): ParsedGiftCard

    /**
     * Syncs the card's own temporary wallet and reports what it holds. A card holding no more than the fee a
     * redemption would pay is [GiftCardStatus.Empty].
     *
     * @throws GiftCardException.InUse when another redemption of the same card is still using its wallet
     * @throws GiftCardException.UnknownHandle when the card was closed
     * @throws GiftCardException.CheckFailed when the card's wallet could not be created or synced
     */
    suspend fun check(handle: GiftCardHandle): GiftCardStatus

    /**
     * Sends everything the card holds to [toAddress], with a memo that identifies the transaction as a gift card
     * redemption (and carries the card's message, if any) in the user's history. [toAddress] must therefore be a
     * shielded address. [beforeSubmission] is called after preparation, just before entering the SDK's redeem call.
     *
     * @throws GiftCardException.NotChecked when no [check] of this card has completed
     * @throws GiftCardException.NothingToRedeem when the card holds nothing spendable above the fee
     * @throws GiftCardException.InUse when another redemption of the same card is still using its wallet
     * @throws GiftCardException.SubmitFailed when the network did not accept the redemption, or creating or
     * submitting it failed in any other way; the card is reset so that the next check sees its true state
     */
    suspend fun redeem(
        handle: GiftCardHandle,
        toAddress: String,
        beforeSubmission: () -> Unit = {},
    ): GiftCardRedemption

    /**
     * Forgets the card, erases the card's temporary wallet data and wipes the card's key from memory. Safe to call
     * more than once and for unknown handles. Does not suspend: the erase completes in the implementation's own scope
     * so that it also runs when the caller's scope is being cancelled.
     */
    fun close(handle: GiftCardHandle)

    /**
     * Waits for queued card wallet closures and confirms that their files were removed within a bounded wait.
     * A failed SDK close leaves reset blocked until the process, and its SDK state, are restarted.
     */
    suspend fun awaitPendingCloses()

    /** Called while a transport settings writer prevents new gift operations from starting. */
    suspend fun canChangeTorMode(): Boolean

    /**
     * The card wallets whose data is stored on the device, on every network, whether or not a card is held for them.
     */
    suspend fun findStoredCardWallets(): List<StoredCardWallet>

    /**
     * Deletes the data of a card wallet found by [findStoredCardWallets].
     */
    suspend fun eraseCardWallet(wallet: StoredCardWallet)
}

/**
 * A card held by a [GiftCardDataSource].
 *
 * @param walletAlias the alias of the card's temporary wallet: the same for every link of the same card.
 */
data class ParsedGiftCard(
    val summary: GiftCardSummary,
    val walletAlias: String
)

/**
 * A card wallet stored on the device.
 */
data class StoredCardWallet(
    val network: ZcashNetwork,
    val alias: String
)

/**
 * SDK-backed implementation: a [GiftCardRedeemer] per parsed card, which runs the card's temporary wallet beside
 * the main one, on the same sync engine ([GiftCardRedeemers]). The card wallet syncs from, and submits to, the
 * lightwalletd endpoint the main synchronizer is using, which is the one stored in the [PersistableWalletProvider]'s
 * wallet (the main synchronizer is rebuilt from that same wallet whenever it changes). It also connects the way the
 * main synchronizer does: over Tor exactly when the user's Tor setting ([IsTorEnabledStorageProvider]) is on, so the
 * server never sees the user's IP address next to the card's birthday and claim when the user chose Tor.
 *
 * The redemption is also recorded in the main synchronizer as a trusted transaction (ZIP 315), so the wallet shows
 * the claimed funds at once and can spend them after 3 confirmations rather than 10. Failing to record it does not
 * fail the redemption: the wallet then finds the funds on its next sync, as an ordinary receive.
 *
 * Redeemers are keyed by [GiftCardHandle]; the SDK keys the card wallet's data on disk by an alias derived from
 * the card itself ([ParsedGiftCard.walletAlias]), so the caller must not use two cards with the same alias at once.
 * Operations on a new redeemer wait for any earlier close of the same alias.
 *
 * Closing (which erases the card wallet's data) happens in this data source's own scope, so it still runs when the
 * caller's scope is cancelled; the SDK cancels an in-flight check and serializes the close after any in-flight
 * redemption on the same redeemer.
 */
@Suppress("TooManyFunctions") // Route selection and closure share ownership of the SDK gift wallet.
class GiftCardDataSourceImpl(
    private val application: Application,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val synchronizerProvider: SynchronizerProvider,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val walletTransport: WalletTransportAccess = WalletTransportAccess(),
) : GiftCardDataSource {
    internal var scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Guards held cards and card-wallet closure records. */
    private val lock = Any()

    private val cards = mutableMapOf<GiftCardHandle, HeldCard>()

    /** The latest close of each alias' card wallet, so that a new redeemer for the alias can wait for it. */
    private val closeJobs = mutableMapOf<String, Job>()

    private val closedWallets = mutableSetOf<StoredCardWallet>()
    private var closeFailed = false

    @Suppress("TooGenericExceptionCaught")
    override suspend fun parse(link: String): ParsedGiftCard = walletTransport.whileOperating {
        val wallet = persistableWalletProvider.getPersistableWallet() ?: throw GiftCardException.NotAvailable()
        val card =
            try {
                GiftCard.parse(link)
            } catch (e: SdkGiftCardException.InvalidLink) {
                throw e.toAppException()
            }
        val route =
            try {
                if (card.network != wallet.network) throw GiftCardException.WrongNetwork()
                newRedeemer(
                    application, card, wallet.network, wallet.endpoint,
                    usesTor = isTorEnabledStorageProvider.get() == true
                )
            } catch (e: Exception) {
                card.wipe()
                throw e
            }

        val handle = GiftCardHandle(UUID.randomUUID().toString())
        val held = HeldCard(card = card, network = wallet.network, route = route)
        synchronized(lock) {
            held.priorClose = closeJobs[route.redeemer.alias]
            cards[handle] = held
        }
        ParsedGiftCard(
            summary =
                GiftCardSummary(
                    handle = handle,
                    origin = card.origin.toAppOrigin(),
                    birthdayHeight = card.birthdayHeight.value,
                    statedAmount = card.statedAmount,
                    message = card.description
                ),
            walletAlias = route.redeemer.alias
        )
    }

    override suspend fun check(handle: GiftCardHandle): GiftCardStatus = walletTransport.whileOperating {
        val held = synchronized(lock) { cards[handle] } ?: throw GiftCardException.UnknownHandle()
        val redeemer = readyRedeemer(handle, held, allowRouteChange = true)
        val status =
            try {
                redeemer.check()
            } catch (e: SdkGiftCardException.Closed) {
                throw GiftCardException.UnknownHandle(e)
            } catch (e: SdkGiftCardException.InUse) {
                throw GiftCardException.InUse(e)
            } catch (e: SdkGiftCardException.SyncFailed) {
                throw GiftCardException.CheckFailed(e)
            }
        when (status) {
            is GiftCardRedeemer.Status.Ready -> {
                GiftCardStatus.Ready(spendable = status.balance.spendable, redeemable = status.redeemable)
            }

            is GiftCardRedeemer.Status.Pending -> {
                GiftCardStatus.Pending(pending = status.balance.pending)
            }

            GiftCardRedeemer.Status.Empty -> {
                GiftCardStatus.Empty
            }
        }
    }

    /**
     * Any failure of the SDK's redeem other than its typed refusals, which come before anything is created, may come
     * after the redemption's transaction was created and stored in the card wallet, where it holds the card's notes.
     * Such a failure is handled like an unsubmitted redemption: the redeemer is replaced, per the SDK contract. So is
     * a cancellation from inside the SDK while the caller itself is not cancelled.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun redeem(
        handle: GiftCardHandle,
        toAddress: String,
        beforeSubmission: () -> Unit,
    ): GiftCardRedemption = walletTransport.whileOperating {
        val held = synchronized(lock) { cards[handle] } ?: throw GiftCardException.UnknownHandle()
        val recipient = RecipientAddress.new(toAddress, held.network)
        val memo = redeemMemo(label = application.getString(R.string.redeemGift_memo), message = held.card.description)
        val redeemer = readyRedeemer(handle, held, allowRouteChange = false)
        val destination = synchronizerProvider.synchronizer.value
        currentCoroutineContext().ensureActive()
        beforeSubmission()
        val redemption =
            try {
                redeemer.redeem(recipient, memo, destination)
            } catch (e: SdkGiftCardException.Closed) {
                throw GiftCardException.UnknownHandle(e)
            } catch (e: SdkGiftCardException.NetworkMismatch) {
                throw GiftCardException.WrongNetwork(e)
            } catch (e: SdkGiftCardException.NotChecked) {
                throw GiftCardException.NotChecked(e)
            } catch (e: SdkGiftCardException.NothingToRedeem) {
                throw GiftCardException.NothingToRedeem(e)
            } catch (e: SdkGiftCardException.InUse) {
                throw GiftCardException.InUse(e)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                replaceRedeemer(handle, held)
                throw GiftCardException.SubmitFailed(e)
            } catch (e: Exception) {
                replaceRedeemer(handle, held)
                throw GiftCardException.SubmitFailed(e)
            }
        val result = redemption.results.firstOrNull()
        if (result == null || !redemption.isSubmitted) {
            replaceRedeemer(handle, held)
            throw GiftCardException.SubmitFailed()
        }
        if (!redemption.recordedInDestination) {
            Twig.warn { "Gift card redemption not recorded in the wallet as trusted; it will be found on sync" }
        }
        GiftCardRedemption(txId = result.txIdString(), received = redemption.amount)
    }

    override fun close(handle: GiftCardHandle) {
        synchronized(lock) {
            cards.remove(handle)?.let { closeLocked(it.route.redeemer, network = it.network, wipe = it.card) }
        }
    }

    override suspend fun awaitPendingCloses() {
        val closed = withTimeoutOrNull(CARD_WALLET_CLOSE_TIMEOUT) {
            synchronized(lock) { closeJobs.values.toList() }.joinAll()
            val targets = synchronized(lock) {
                if (closeFailed) throw GiftWalletRestartRequiredException()
                closedWallets.groupBy { it.network }
            }
            // SDK close may return while its teardown erases the helper wallet in the background.
            for ((network, wallets) in targets) {
                val aliases = wallets.map { it.alias }.toSet()
                while (GiftCardRedeemers.storedAliases(application, network).any(aliases::contains)) {
                    delay(CARD_WALLET_ERASE_CHECK_INTERVAL)
                }
            }
            true
        }
        check(closed == true) { "Timed out waiting for gift card wallet closure" }
        synchronized(lock) { closedWallets.clear() }
    }

    override suspend fun canChangeTorMode(): Boolean {
        if (synchronized(lock) { cards.isNotEmpty() }) return false
        awaitPendingCloses()
        return true
    }

    override suspend fun findStoredCardWallets(): List<StoredCardWallet> =
        listOf(ZcashNetwork.Mainnet, ZcashNetwork.Testnet).flatMap { network ->
            GiftCardRedeemers.storedAliases(application, network).map { StoredCardWallet(network, it) }
        }

    override suspend fun eraseCardWallet(wallet: StoredCardWallet) {
        GiftCardRedeemers.erase(application, wallet.network, wallet.alias)
    }

    /**
     * Match the current server and Tor setting before entering the SDK. A changed route requires a fresh
     * check before redemption. Read again after waiting for the previous redeemer's close.
     */
    private suspend fun readyRedeemer(
        handle: GiftCardHandle,
        held: HeldCard,
        allowRouteChange: Boolean,
    ): GiftCardRedeemer {
        val requested = readRoute(held)
        val (route, priorClose) = synchronized(lock) {
            if (cards[handle] !== held) {
                null
            } else {
                if (allowRouteChange && held.route.settings != requested) replaceRouteLocked(held, requested)
                held.route to held.priorClose
            }
        } ?: throw GiftCardException.UnknownHandle()
        priorClose?.join()
        val current = readRoute(held)
        val refusal = synchronized(lock) {
            when {
                cards[handle] !== held -> GiftCardException.UnknownHandle()
                held.route !== route || route.settings != current -> GiftCardException.NotChecked()
                else -> null
            }
        }
        if (refusal != null) throw refusal
        return route.redeemer
    }

    private suspend fun readRoute(held: HeldCard): GiftWalletRoute {
        val wallet = persistableWalletProvider.getPersistableWallet() ?: throw GiftCardException.NotAvailable()
        if (wallet.network != held.network) throw GiftCardException.WrongNetwork()
        return GiftWalletRoute(wallet.endpoint, isTorEnabledStorageProvider.get() == true)
    }

    /**
     * Drops the card wallet that holds an unsubmitted redemption and starts over with a fresh redeemer for the same
     * card, without wiping it, per the SDK contract, so that the next check sees the card's true state. Does nothing
     * for a card closed meanwhile, whose redeemer is already being closed. A fresh redeemer that cannot be created
     * leaves the old one in place, after logging.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun replaceRedeemer(
        handle: GiftCardHandle,
        held: HeldCard
    ) {
        try {
            val requested = readRoute(held)
            synchronized(lock) {
                if (cards[handle] === held) replaceRouteLocked(held, requested)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Twig.error { "Replacing a gift card redeemer failed: ${e::class.simpleName}" }
        }
    }

    private fun replaceRouteLocked(held: HeldCard, requested: GiftWalletRoute) {
        val fresh = newRedeemer(application, held.card, held.network, requested.endpoint, requested.usesTor)
        closeLocked(held.route.redeemer, network = held.network, wipe = null)
        held.priorClose = closeJobs[fresh.redeemer.alias]
        held.route = fresh
    }

    /**
     * Closes [redeemer] in [scope], after any earlier close of the same alias, then wipes the key of [wipe], if
     * given. Call with [lock] held.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun closeLocked(
        redeemer: GiftCardRedeemer,
        network: ZcashNetwork,
        wipe: GiftCard?
    ) {
        val alias = redeemer.alias
        closedWallets += StoredCardWallet(network, alias)
        val previous = closeJobs[alias]
        val job =
            scope.launch {
                previous?.join()
                try {
                    // SDK close can return after its own 30-second wait while teardown is still running.
                    val confirmed = withTimeoutOrNull(CARD_WALLET_CLOSE_CONFIRMATION_TIMEOUT) {
                        redeemer.close()
                        true
                    }
                    if (confirmed != true) throw GiftWalletRestartRequiredException()
                } catch (e: Exception) {
                    synchronized(lock) { closeFailed = true }
                    Twig.error { "Closing a gift card wallet failed: ${e::class.simpleName}" }
                } finally {
                    wipe?.wipe()
                }
            }
        closeJobs[alias] = job
        job.invokeOnCompletion {
            synchronized(lock) { if (closeJobs[alias] === job) closeJobs.remove(alias) }
        }
    }

    /** Route and prior close are guarded by [lock]. */
    private class HeldCard(
        val card: GiftCard,
        val network: ZcashNetwork,
        var route: RoutedRedeemer,
    ) {
        var priorClose: Job? = null
    }
}

private data class GiftWalletRoute(val endpoint: LightWalletEndpoint, val usesTor: Boolean)

private data class RoutedRedeemer(val redeemer: GiftCardRedeemer, val settings: GiftWalletRoute)

private fun newRedeemer(
    application: Application,
    card: GiftCard,
    network: ZcashNetwork,
    endpoint: LightWalletEndpoint,
    usesTor: Boolean,
): RoutedRedeemer =
    try {
        RoutedRedeemer(
            GiftCardRedeemers.new(
                context = application,
                card = card,
                network = network,
                lightWalletEndpoint = endpoint,
                isTorEnabled = usesTor
            ),
            settings = GiftWalletRoute(endpoint, usesTor)
        )
    } catch (e: SdkGiftCardException.NetworkMismatch) {
        throw GiftCardException.WrongNetwork(e)
    }

/**
 * The memo on the redeem transaction: the localized "Gift Card" [label], followed by the card's [message] when it
 * has one. The message is the SDK's [GiftCard.description], which the SDK has already sanitized (no control or
 * invisible characters, never blank); here it is only cut on a UTF-8 character boundary so that the memo always fits
 * [MemoContent.MAX_MEMO_LENGTH_BYTES]. A card's message is never a reason to fail the redemption.
 */
private fun redeemMemo(
    label: String,
    message: String?
): MemoContent {
    val memo =
        if (message == null) {
            label
        } else {
            val prefix = label + GIFT_CARD_MEMO_SEPARATOR
            prefix + message.truncatedToUtf8Bytes(MemoContent.MAX_MEMO_LENGTH_BYTES - MemoContent.length(prefix))
        }
    return MemoContent.fromString(memo)
}

/**
 * The longest prefix of this string that encodes to at most [maxBytes] UTF-8 bytes, cut on a code point boundary:
 * the cut steps back over the continuation bytes (`10xxxxxx`) of a code point cut in the middle.
 */
private fun String.truncatedToUtf8Bytes(maxBytes: Int): String {
    val bytes = toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return this
    var end = maxBytes.coerceAtLeast(0)
    while (end > 0 && (bytes[end].toInt() and CONTINUATION_BYTE_MASK) == CONTINUATION_BYTE) end--
    return String(bytes, 0, end, Charsets.UTF_8)
}

internal class GiftWalletRestartRequiredException : IllegalStateException("Gift card wallet close requires a restart")

private const val CONTINUATION_BYTE_MASK = 0xC0
private const val CONTINUATION_BYTE = 0x80
private val CARD_WALLET_ERASE_CHECK_INTERVAL = 100.milliseconds
private val CARD_WALLET_CLOSE_TIMEOUT = 30.seconds
private val CARD_WALLET_CLOSE_CONFIRMATION_TIMEOUT = 28.seconds

private fun SdkGiftCardException.InvalidLink.toAppException(): GiftCardException =
    when (reason) {
        GiftCardLinkError.NetworkMismatch,
        GiftCardLinkError.UnsupportedNetwork -> GiftCardException.WrongNetwork()

        GiftCardLinkError.NotAGiftLink,
        GiftCardLinkError.UnsupportedVersion,
        GiftCardLinkError.MissingField,
        GiftCardLinkError.DuplicateField,
        GiftCardLinkError.InvalidField,
        GiftCardLinkError.TooLong,
        GiftCardLinkError.KeyDerivation,
        GiftCardLinkError.Unknown -> GiftCardException.InvalidLink(this)
    }

private fun SdkGiftCardOrigin.toAppOrigin(): GiftCardOrigin =
    when (this) {
        SdkGiftCardOrigin.Zodl -> GiftCardOrigin.ZODL

        SdkGiftCardOrigin.LegacyV1,
        SdkGiftCardOrigin.LegacyV2,
        SdkGiftCardOrigin.LegacyV3 -> GiftCardOrigin.VIZOR
    }
