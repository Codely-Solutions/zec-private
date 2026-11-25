package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.GiftCardRedeemer
import cash.z.ecc.android.sdk.GiftCardRedeemers
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.GiftCardLinkError
import cash.z.ecc.android.sdk.model.MemoContent
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.ADDRESS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.ALIAS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.AMOUNT
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.BIRTHDAY
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.CLOSE_DURATION_MS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.ENDPOINT
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.FEE
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.LINK
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.MEMO_LABEL
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.MESSAGE
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.OTHER_ALIAS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.OTHER_LINK
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.TX_ID
import co.electriccoin.zcash.ui.common.model.GiftCardException
import co.electriccoin.zcash.ui.common.model.GiftCardHandle
import co.electriccoin.zcash.ui.common.model.GiftCardOrigin
import co.electriccoin.zcash.ui.common.model.GiftCardRedemption
import co.electriccoin.zcash.ui.common.model.GiftCardStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import cash.z.ecc.android.sdk.exception.GiftCardException as SdkGiftCardException
import cash.z.ecc.android.sdk.model.GiftCardOrigin as SdkGiftCardOrigin

/**
 * [GiftCardDataSourceImpl] against a mocked SDK: link errors and statuses map to the app's types, the card wallet
 * is created, listed and erased through the engine-backed [GiftCardRedeemers] and uses the main wallet's network,
 * endpoint and Tor setting, redemptions are recorded in the main wallet, redeemers
 * are closed exactly once and the card's key is wiped when it is closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GiftCardDataSourceImplTest : GiftCardDataSourceTestFixture() {
    @Test
    fun parseCreatesTheRedeemerForTheMainWalletsNetworkAndEndpoint() =
        runTest {
            val parsed = dataSource().parse(LINK)
            val summary = parsed.summary

            assertEquals(ALIAS, parsed.walletAlias)
            assertEquals(GiftCardOrigin.VIZOR, summary.origin)
            assertEquals(BIRTHDAY, summary.birthdayHeight)
            assertEquals(Zatoshi(AMOUNT), summary.statedAmount)
            assertEquals(MESSAGE, summary.message)
            verify(exactly = 1) {
                GiftCardRedeemers.new(
                    context = any(),
                    card = any(),
                    network = ZcashNetwork.Mainnet,
                    lightWalletEndpoint = ENDPOINT,
                    isTorEnabled = any(),
                    alias = any()
                )
            }
        }

    @Test
    fun theCardWalletUsesTorWhenTheMainWalletDoes() =
        runTest {
            dataSource().parse(LINK)

            verify(exactly = 1) {
                GiftCardRedeemers.new(any(), any(), any(), any(), isTorEnabled = true, alias = any())
            }
        }

    @Test
    fun theCardWalletConnectsDirectlyWhenTorIsOff() =
        runTest {
            coEvery { isTorEnabledStorageProvider.get() } returns false

            dataSource().parse(LINK)

            verify(exactly = 1) {
                GiftCardRedeemers.new(any(), any(), any(), any(), isTorEnabled = false, alias = any())
            }
        }

    @Test
    fun anUnsetTorSettingConnectsDirectlyLikeTheMainWallet() =
        runTest {
            coEvery { isTorEnabledStorageProvider.get() } returns null

            dataSource().parse(LINK)

            verify(exactly = 1) {
                GiftCardRedeemers.new(any(), any(), any(), any(), isTorEnabled = false, alias = any())
            }
        }

    @Test
    fun enablingTorRechecksWithTheNewRouteBeforeRedemption() =
        runTest {
            coEvery { isTorEnabledStorageProvider.get() } returns false
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            val events = mutableListOf<String>()
            coEvery { redeemer.close() } coAnswers { events += "old closed" }
            coEvery { replacementRedeemer.check() } coAnswers {
                events += "new checked"
                GiftCardRedeemer.Status.Empty
            }

            coEvery { isTorEnabledStorageProvider.get() } returns true
            assertFailsWith<GiftCardException.NotChecked> {
                dataSource.redeem(handle, ADDRESS) { error("Stale route reached admission") }
            }
            assertEquals(GiftCardStatus.Empty, dataSource.check(handle))

            assertEquals(listOf("old closed", "new checked"), events)
            coVerify(exactly = 0) { redeemer.check() }
            coVerify(exactly = 0) { redeemer.redeem(any(), any(), any()) }
            verify(exactly = 1) {
                GiftCardRedeemers.new(any(), any(), any(), any(), isTorEnabled = true, alias = any())
            }
        }

    @Test
    fun aFailedTorReconfigurationCannotCheckWithTheOldDirectRoute() =
        runTest {
            coEvery { isTorEnabledStorageProvider.get() } returns false
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            val cause = IllegalStateException("synthetic Tor setup failure")
            every {
                GiftCardRedeemers.new(any(), any(), any(), any(), isTorEnabled = true, alias = any())
            } throws cause

            coEvery { isTorEnabledStorageProvider.get() } returns true
            assertSame(cause, assertFailsWith<IllegalStateException> { dataSource.check(handle) })
            coVerify(exactly = 0) { redeemer.check() }
        }

    @Test
    fun aSettingChangeWhileTheOldRouteClosesRequiresAnotherCheck() =
        runTest {
            var enabled = false
            coEvery { isTorEnabledStorageProvider.get() } answers { enabled }
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.close() } coAnswers { enabled = false }
            enabled = true

            assertFailsWith<GiftCardException.NotChecked> { dataSource.check(handle) }
            coVerify(exactly = 0) { redeemer.check() }
            coVerify(exactly = 0) { replacementRedeemer.check() }
        }

    @Test
    fun theFreshRedeemerAfterAnUnsubmittedRedeemAlsoUsesTor() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } returns
                GiftCardRedeemer.Redemption(
                    fee = Zatoshi(FEE),
                    results = listOf(TransactionSubmitResult.NotAttempted(TX_ID))
                )

            assertFailsWith<GiftCardException.SubmitFailed> { dataSource.redeem(handle, ADDRESS) }

            verify(exactly = 2) {
                GiftCardRedeemers.new(any(), any(), any(), any(), isTorEnabled = true, alias = any())
            }
        }

    @Test
    fun eachParseGetsItsOwnHandle() =
        runTest {
            coEvery { GiftCard.parse(OTHER_LINK) } returns card(ZcashNetwork.Mainnet)
            every { replacementRedeemer.alias } returns OTHER_ALIAS
            val dataSource = dataSource()

            assertNotEquals(dataSource.parse(LINK).summary.handle, dataSource.parse(OTHER_LINK).summary.handle)
        }

    @Test
    fun malformedLinkIsInvalid() =
        runTest {
            val cause = SdkGiftCardException.InvalidLink(GiftCardLinkError.MissingField)
            coEvery { GiftCard.parse(LINK) } throws cause

            val e = assertFailsWith<GiftCardException.InvalidLink> { dataSource().parse(LINK) }

            assertSame(cause, e.cause)
            assertEquals(0, newRedeemerCalls)
        }

    @Test
    fun linkForAnotherNetworkIsWrongNetwork() =
        runTest {
            coEvery { GiftCard.parse(LINK) } throws
                SdkGiftCardException.InvalidLink(GiftCardLinkError.NetworkMismatch)

            assertFailsWith<GiftCardException.WrongNetwork> { dataSource().parse(LINK) }
        }

    @Test
    fun cardForAnotherNetworkThanTheWalletIsWrongNetwork() =
        runTest {
            coEvery { GiftCard.parse(LINK) } returns card(ZcashNetwork.Testnet)

            assertFailsWith<GiftCardException.WrongNetwork> { dataSource().parse(LINK) }
            assertEquals(0, newRedeemerCalls)
        }

    @Test
    fun withoutAWalletRedemptionIsNotAvailable() =
        runTest {
            coEvery { persistableWalletProvider.getPersistableWallet() } returns null

            assertFailsWith<GiftCardException.NotAvailable> { dataSource().parse(LINK) }
        }

    @Test
    fun checkMapsTheCardsStatus() =
        runTest {
            val dataSource = dataSource()
            val handle = dataSource.parse(LINK).summary.handle
            val balance =
                GiftCardRedeemer.Balance(total = Zatoshi(300), spendable = Zatoshi(200), pending = Zatoshi(100))

            coEvery { redeemer.check() } returns GiftCardRedeemer.Status.Ready(balance, fee = Zatoshi(150))
            assertEquals(
                GiftCardStatus.Ready(spendable = Zatoshi(200), redeemable = Zatoshi(50)),
                dataSource.check(handle)
            )

            coEvery { redeemer.check() } returns GiftCardRedeemer.Status.Pending(balance)
            assertEquals(GiftCardStatus.Pending(Zatoshi(100)), dataSource.check(handle))

            coEvery { redeemer.check() } returns GiftCardRedeemer.Status.Empty
            assertEquals(GiftCardStatus.Empty, dataSource.check(handle))
        }

    @Test
    fun aCardHoldingNoMoreThanTheFeeIsEmpty() =
        runTest {
            val dataSource = dataSource()
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.check() } returns GiftCardRedeemer.Status.Empty

            assertEquals(GiftCardStatus.Empty, dataSource.check(handle))
        }

    @Test
    fun aCardWhoseWalletIsInUseIsReportedAsInUse() =
        runTest {
            val dataSource = dataSource()
            val handle = dataSource.parse(LINK).summary.handle
            val cause = SdkGiftCardException.InUse()
            coEvery { redeemer.check() } throws cause

            val e = assertFailsWith<GiftCardException.InUse> { dataSource.check(handle) }
            assertSame(cause, e.cause)
        }

    @Test
    fun aFailedCardWalletSyncReachesTheScreenAsACheckFailure() =
        runTest {
            val dataSource = dataSource()
            val handle = dataSource.parse(LINK).summary.handle
            val cause = SdkGiftCardException.SyncFailed(null)
            coEvery { redeemer.check() } throws cause

            assertSame(cause, assertFailsWith<GiftCardException.CheckFailed> { dataSource.check(handle) }.cause)
        }

    /**
     * The SDK's redeem can throw after the redemption's transaction was created and stored in the card wallet, where
     * it holds the card's notes: checking that wallet again would show the card as empty or pending while its funds
     * are still on it. The card must start over with a fresh redeemer, without being wiped.
     */
    @Test
    fun aRedeemThatThrowsStartsOverWithAFreshRedeemer() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            val cause = IllegalStateException("no connection to the server")
            coEvery { redeemer.redeem(recipient, any(), any()) } throws cause

            val failure = assertFailsWith<GiftCardException.SubmitFailed> { dataSource.redeem(handle, ADDRESS) }
            assertSame(cause, failure.cause)
            advanceUntilIdle()

            coVerify(exactly = 1) { redeemer.close() }
            verify(exactly = 0) { parsedCard.wipe() }
            assertEquals(2, newRedeemerCalls)
            coEvery { replacementRedeemer.check() } returns GiftCardRedeemer.Status.Empty
            assertEquals(GiftCardStatus.Empty, dataSource.check(handle))
        }

    @Test
    fun aCancellationFromInsideTheRedeemerAlsoStartsOver() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } throws CancellationException("foreign")

            assertFailsWith<GiftCardException.SubmitFailed> { dataSource.redeem(handle, ADDRESS) }
            advanceUntilIdle()

            coVerify(exactly = 1) { redeemer.close() }
            assertEquals(2, newRedeemerCalls)
        }

    @Test
    fun aRedeemerThatCannotBeReplacedLeavesTheCardWithItsRedeemer() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } throws IllegalStateException("submit")
            every { GiftCardRedeemers.new(any(), any(), any(), any(), any(), any()) } throws
                IllegalStateException("synthetic redeemer creation failure")

            assertFailsWith<GiftCardException.SubmitFailed> { dataSource.redeem(handle, ADDRESS) }
            advanceUntilIdle()

            coVerify(exactly = 0) { redeemer.close() }
            coEvery { redeemer.check() } returns GiftCardRedeemer.Status.Empty
            assertEquals(GiftCardStatus.Empty, dataSource.check(handle))
        }

    @Test
    fun aCancelledReplacementIsNotMistakenForAFailedOne() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } throws IllegalStateException("submit")
            every { GiftCardRedeemers.new(any(), any(), any(), any(), any(), any()) } throws
                CancellationException("synthetic redeemer creation cancellation")

            assertFailsWith<CancellationException> { dataSource.redeem(handle, ADDRESS) }
            coVerify(exactly = 1) { redeemer.redeem(recipient, any(), any()) }
            assertEquals(1, newRedeemerCalls)
        }

    @Test
    fun aCheckWaitsForAnEarlierCloseOfTheSameCardWallet() =
        runTest {
            val events = mutableListOf<String>()
            coEvery { redeemer.close() } coAnswers {
                delay(CLOSE_DURATION_MS)
                events += "closed"
            }
            coEvery { replacementRedeemer.check() } coAnswers {
                events += "checked"
                GiftCardRedeemer.Status.Empty
            }
            val dataSource = dataSource(this)
            dataSource.close(dataSource.parse(LINK).summary.handle)
            val handle = dataSource.parse(LINK).summary.handle

            dataSource.check(handle)

            assertEquals(listOf("closed", "checked"), events)
        }

    @Test
    fun redeemBeforeACheckIsNotChecked() =
        runTest {
            val dataSource = dataSource()
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } throws SdkGiftCardException.NotChecked()

            assertFailsWith<GiftCardException.NotChecked> { dataSource.redeem(handle, ADDRESS) }
        }

    @Test
    fun redeemOfACardInUseIsInUse() =
        runTest {
            val dataSource = dataSource()
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } throws SdkGiftCardException.InUse()

            assertFailsWith<GiftCardException.InUse> { dataSource.redeem(handle, ADDRESS) }
        }

    @Test
    fun redeemOfADustCardIsNothingToRedeem() =
        runTest {
            val dataSource = dataSource()
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } throws SdkGiftCardException.NothingToRedeem()

            assertFailsWith<GiftCardException.NothingToRedeem> { dataSource.redeem(handle, ADDRESS) }
        }

    @Test
    fun unknownHandleIsRejected() =
        runTest {
            val dataSource = dataSource()

            assertFailsWith<GiftCardException.UnknownHandle> { dataSource.check(GiftCardHandle("nope")) }
            assertFailsWith<GiftCardException.UnknownHandle> { dataSource.redeem(GiftCardHandle("nope"), ADDRESS) }
        }

    @Test
    fun redeemSendsToTheValidatedAddressAndReturnsTheTxId() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } returns
                GiftCardRedeemer.Redemption(fee = Zatoshi(FEE), results = listOf(success(TX_ID)))

            assertEquals(success(TX_ID).txIdString(), dataSource.redeem(handle, ADDRESS).txId)
            assertEquals(1, newRedeemerCalls)
        }

    @Test
    fun redeemRecordsTheClaimInTheMainWallet() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), mainSynchronizer) } returns
                GiftCardRedeemer.Redemption(
                    fee = Zatoshi(FEE),
                    results = listOf(success(TX_ID)),
                    recordedInDestination = true
                )

            assertEquals(success(TX_ID).txIdString(), dataSource.redeem(handle, ADDRESS).txId)
            coVerify(exactly = 1) { redeemer.redeem(recipient, any(), mainSynchronizer) }
        }

    @Test
    fun redeemSucceedsWhenTheMainWalletDidNotRecordTheClaim() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), mainSynchronizer) } returns
                GiftCardRedeemer.Redemption(
                    fee = Zatoshi(FEE),
                    results = listOf(success(TX_ID)),
                    recordedInDestination = false
                )

            assertEquals(success(TX_ID).txIdString(), dataSource.redeem(handle, ADDRESS).txId)
            advanceUntilIdle()
            coVerify(exactly = 0) { redeemer.close() }
            assertEquals(1, newRedeemerCalls)
        }

    @Test
    fun redeemIntoAWalletOnAnotherNetworkIsWrongNetwork() =
        runTest {
            val dataSource = dataSource()
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } throws SdkGiftCardException.NetworkMismatch()

            assertFailsWith<GiftCardException.WrongNetwork> { dataSource.redeem(handle, ADDRESS) }
        }

    @Test
    fun redeemMemoNamesTheGiftCardAndItsMessage() =
        runTest {
            assertEquals(MemoContent.fromString("$MEMO_LABEL · $MESSAGE"), redeemMemo(message = MESSAGE))
        }

    @Test
    fun redeemMemoNamesTheGiftCardWhenItHasNoMessage() =
        runTest {
            assertEquals(MemoContent.fromString(MEMO_LABEL), redeemMemo(message = null))
        }

    /**
     * 300 two-byte characters: 600 bytes. After the 13-byte prefix, 499 bytes are left for the message, which is not
     * a multiple of two, so the cut must step back to a character boundary.
     */
    @Test
    fun redeemMemoCutsALongMessageOnACharacterBoundary() =
        runTest {
            val message = "é".repeat(300)
            assertEquals(600, MemoContent.length(message))

            val memo = redeemMemo(message)

            val text = memo.toStringOrNull()!!
            assertEquals("$MEMO_LABEL · " + "é".repeat(249), text)
            assertEquals(511, MemoContent.length(text))
        }

    /** Parses a card with [message] and redeems it, returning the memo given to the redeemer. */
    private suspend fun TestScope.redeemMemo(message: String?): MemoContent {
        coEvery { GiftCard.parse(LINK) } returns card(ZcashNetwork.Mainnet, message = message)
        val dataSource = dataSource(this)
        val handle = dataSource.parse(LINK).summary.handle
        val memo = slot<MemoContent?>()
        coEvery { redeemer.redeem(recipient, captureNullable(memo), any()) } returns
            GiftCardRedeemer.Redemption(fee = Zatoshi(FEE), results = listOf(success(TX_ID)))

        dataSource.redeem(handle, ADDRESS)

        return assertNotNull(memo.captured)
    }

    @Test
    fun unsubmittedRedeemFailsAndStartsOverWithAFreshRedeemer() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } returns
                GiftCardRedeemer.Redemption(
                    fee = Zatoshi(FEE),
                    results = listOf(TransactionSubmitResult.NotAttempted(TX_ID))
                )

            assertFailsWith<GiftCardException.SubmitFailed> { dataSource.redeem(handle, ADDRESS) }
            advanceUntilIdle()

            coVerify(exactly = 1) { redeemer.close() }
            assertEquals(2, newRedeemerCalls)
            coEvery { replacementRedeemer.check() } returns GiftCardRedeemer.Status.Empty
            assertEquals(GiftCardStatus.Empty, dataSource.check(handle))
        }

    @Test
    fun closeClosesTheRedeemerOnceWipesTheCardAndForgetsTheHandle() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle

            dataSource.close(handle)
            dataSource.close(handle)
            dataSource.close(GiftCardHandle("nope"))
            advanceUntilIdle()

            coVerify(exactly = 1) { redeemer.close() }
            verify(exactly = 1) { parsedCard.wipe() }
            assertFailsWith<GiftCardException.UnknownHandle> { dataSource.check(handle) }
        }

    @Test
    fun theCardIsNotWipedWhenOnlyItsRedeemerIsReplaced() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } returns
                GiftCardRedeemer.Redemption(
                    fee = Zatoshi(FEE),
                    results = listOf(TransactionSubmitResult.NotAttempted(TX_ID))
                )

            assertFailsWith<GiftCardException.SubmitFailed> { dataSource.redeem(handle, ADDRESS) }
            advanceUntilIdle()

            verify(exactly = 0) { parsedCard.wipe() }
        }

    @Test
    fun aRedemptionWithoutResultsIsASubmitFailure() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } returns
                GiftCardRedeemer.Redemption(fee = Zatoshi(FEE), results = emptyList())

            assertFailsWith<GiftCardException.SubmitFailed> { dataSource.redeem(handle, ADDRESS) }
            advanceUntilIdle()

            assertEquals(2, newRedeemerCalls)
        }

    @Test
    fun redeemReportsTheAmountReceived() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } returns
                GiftCardRedeemer.Redemption(
                    fee = Zatoshi(FEE),
                    results = listOf(success(TX_ID)),
                    amount = Zatoshi(AMOUNT - FEE)
                )

            assertEquals(
                GiftCardRedemption(txId = success(TX_ID).txIdString(), received = Zatoshi(AMOUNT - FEE)),
                dataSource.redeem(handle, ADDRESS)
            )
        }

    @Test
    fun storedCardWalletsAreListedOnEveryNetworkAndErasedByAlias() =
        runTest {
            coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Mainnet) } returns setOf(ALIAS)
            coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Testnet) } returns setOf(OTHER_ALIAS)
            coEvery { GiftCardRedeemers.erase(any(), any(), any()) } returns true
            val dataSource = dataSource()

            val stored = dataSource.findStoredCardWallets()

            assertEquals(
                listOf(
                    StoredCardWallet(ZcashNetwork.Mainnet, ALIAS),
                    StoredCardWallet(ZcashNetwork.Testnet, OTHER_ALIAS)
                ),
                stored
            )
            dataSource.eraseCardWallet(stored.first())
            coVerify(exactly = 1) { GiftCardRedeemers.erase(any(), ZcashNetwork.Mainnet, ALIAS) }
        }

    @Test
    fun parsingTheSameCardAgainLeavesTheEarlierRedeemerAlone() =
        runTest {
            val dataSource = dataSource(this)
            val first = dataSource.parse(LINK).summary.handle
            dataSource.parse(LINK)
            advanceUntilIdle()

            coVerify(exactly = 0) { redeemer.close() }
            coEvery { redeemer.check() } returns GiftCardRedeemer.Status.Empty
            assertEquals(GiftCardStatus.Empty, dataSource.check(first))
        }

    @Test
    fun closedRedeemerIsReportedAsUnknownHandle() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.check() } throws SdkGiftCardException.Closed()

            assertFailsWith<GiftCardException.UnknownHandle> { dataSource.check(handle) }
        }

    @Test
    fun aZodlCardIsReportedAsIssuedByZodl() =
        runTest {
            coEvery { GiftCard.parse(LINK) } returns card(ZcashNetwork.Mainnet, origin = SdkGiftCardOrigin.Zodl)

            assertEquals(GiftCardOrigin.ZODL, dataSource().parse(LINK).summary.origin)
        }

    @Test
    fun aCardTheRedeemerRefusesForItsNetworkIsWrongNetworkAndNotHeld() =
        runTest {
            val cause = SdkGiftCardException.NetworkMismatch()
            every { GiftCardRedeemers.new(any(), any(), any(), any(), any(), any()) } throws cause

            val e = assertFailsWith<GiftCardException.WrongNetwork> { dataSource().parse(LINK) }

            assertSame(cause, e.cause)
        }

    @Test
    fun redeemOfAClosedRedeemerIsUnknownHandle() =
        runTest {
            val dataSource = dataSource()
            val handle = dataSource.parse(LINK).summary.handle
            val cause = SdkGiftCardException.Closed()
            coEvery { redeemer.redeem(recipient, any(), any()) } throws cause

            val e = assertFailsWith<GiftCardException.UnknownHandle> { dataSource.redeem(handle, ADDRESS) }

            assertSame(cause, e.cause)
        }

    @Test
    fun anUnsubmittedRedeemOfACardClosedMeanwhileKeepsTheCardClosed() =
        runTest {
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle
            coEvery { redeemer.redeem(recipient, any(), any()) } answers {
                dataSource.close(handle)
                GiftCardRedeemer.Redemption(
                    fee = Zatoshi(FEE),
                    results = listOf(TransactionSubmitResult.NotAttempted(TX_ID))
                )
            }

            assertFailsWith<GiftCardException.SubmitFailed> { dataSource.redeem(handle, ADDRESS) }
            advanceUntilIdle()

            coVerify(exactly = 1) { redeemer.close() }
            coVerify(exactly = 0) { replacementRedeemer.close() }
            verify(exactly = 1) { parsedCard.wipe() }
            assertFailsWith<GiftCardException.UnknownHandle> { dataSource.check(handle) }
        }

    @Test
    fun aRedeemWaitsForAnEarlierCloseOfTheSameCardWallet() =
        runTest {
            val events = mutableListOf<String>()
            coEvery { redeemer.close() } coAnswers {
                delay(CLOSE_DURATION_MS)
                events += "closed"
            }
            coEvery { replacementRedeemer.redeem(recipient, any(), any()) } coAnswers {
                events += "redeemed"
                GiftCardRedeemer.Redemption(fee = Zatoshi(FEE), results = listOf(success(TX_ID)))
            }
            val dataSource = dataSource(this)
            dataSource.close(dataSource.parse(LINK).summary.handle)
            val handle = dataSource.parse(LINK).summary.handle

            dataSource.redeem(handle, ADDRESS)

            assertEquals(listOf("closed", "redeemed"), events)
        }

    @Test
    fun closesOfTheSameCardWalletRunOneAfterTheOther() =
        runTest {
            val events = mutableListOf<String>()
            coEvery { redeemer.close() } coAnswers {
                delay(CLOSE_DURATION_MS)
                events += "first"
            }
            coEvery { replacementRedeemer.close() } coAnswers { events += "second" }
            val dataSource = dataSource(this)
            val first = dataSource.parse(LINK).summary.handle
            val second = dataSource.parse(LINK).summary.handle

            dataSource.close(first)
            dataSource.close(second)
            advanceUntilIdle()

            assertEquals(listOf("first", "second"), events)
            assertFailsWith<GiftCardException.UnknownHandle> { dataSource.check(first) }
        }

    @Test
    fun aFailingCloseStillWipesTheCardsKey() =
        runTest {
            coEvery { redeemer.close() } throws IllegalStateException("database is locked")
            val dataSource = dataSource(this)
            val handle = dataSource.parse(LINK).summary.handle

            dataSource.close(handle)
            advanceUntilIdle()

            coVerify(exactly = 1) { redeemer.close() }
            verify(exactly = 1) { parsedCard.wipe() }
        }
}
