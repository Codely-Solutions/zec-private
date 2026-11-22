package co.electriccoin.zcash.ui.common.datasource

import android.content.Context
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SimpleSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.metadata.AccountMetadataV3
import co.electriccoin.zcash.ui.common.model.metadata.AnnotationMetadataV3
import co.electriccoin.zcash.ui.common.model.metadata.BookmarkMetadataV3
import co.electriccoin.zcash.ui.common.model.metadata.MetadataV3
import co.electriccoin.zcash.ui.common.model.metadata.SwapsMetadataV3
import co.electriccoin.zcash.ui.common.model.near.NearSwapQuote
import co.electriccoin.zcash.ui.common.model.near.NearSwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.SwapType
import co.electriccoin.zcash.ui.common.model.near.reviewedIdentity
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.MetadataProviderImpl
import co.electriccoin.zcash.ui.common.provider.MetadataKeyStorageProvider
import co.electriccoin.zcash.ui.common.provider.MetadataStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.MetadataRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataEncryptorImpl
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataKey
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataSerializer
import co.electriccoin.zcash.ui.common.usecase.ProcessSwapTransactionUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSwapStatusUseCase
import co.electriccoin.zcash.ui.common.usecase.GetReloadableSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.SwapQuoteStatusData
import co.electriccoin.zcash.ui.common.usecase.SaveORSwapUseCase
import co.electriccoin.zcash.ui.common.mapper.SwapSupportMapper
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailVM
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailArgs
import co.electriccoin.zcash.ui.screen.transactiondetail.CommonTransactionDetailMapper
import co.electriccoin.zcash.ui.R
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.util.SecretBytes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MetadataDataSourceImpl] end to end over real files: the real storage provider over a temporary
 * directory, the real provider and the real encryptor, with a hardware-wallet style key that has
 * more than one entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetadataDataSourceFileTest {
    private val filesDir = createTempDirectory("metadata-data-source-file-test").toFile()
    private val metadataDir = File(filesDir, "metadata")
    private val key = metadataKey(0, 1)
    private val canonicalName = key.fileIdentifiers()[0]
    private val legacyName = key.fileIdentifiers()[1]
    private val storageProvider =
        MetadataStorageProviderImpl(
            directorySync = DirectorySync {},
            context =
                mockk<Context> {
                    every { filesDir } returns this@MetadataDataSourceFileTest.filesDir
                }
        )
    private val metadataProvider =
        MetadataProviderImpl(
            metadataEncryptor = MetadataEncryptorImpl(MetadataSerializer()),
            directorySync = {}
        )
    private val swapAssets = mutableMapOf<String, SimpleSwapAsset>()
    private val simpleSwapAssetProvider =
        mockk<SimpleSwapAssetProvider> {
            every { get(any(), any()) } answers { swapAsset(firstArg(), secondArg()) }
        }

    @AfterTest
    fun deleteTempDir() {
        filesDir.deleteRecursively()
    }

    @Test
    fun anOpenDetailReloadsSettlementAfterAnotherObserverSavedSuccess() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
        val quote = fundingQuote(SwapType.FLEX_INPUT)
        val metadata = repository(metadataProvider)
        SaveORSwapUseCase(mockk(), metadata, mockk()).persistBeforeFunding(quote)
        fun status(value: co.electriccoin.zcash.ui.common.model.near.SwapStatus, time: String) =
            NearSwapQuoteStatus(
                response = SwapStatusResponseDto(
                    quoteResponse = quote.response,
                    status = value,
                    updatedAt = time,
                ),
                origin = quote.originAsset,
                destination = quote.destinationAsset,
                depositAddress = quote.depositAddress,
                destinationAddress = quote.destinationAddress,
                refundAddress = quote.refundAddress,
            )
        val processing = status(
            co.electriccoin.zcash.ui.common.model.near.SwapStatus.PROCESSING, "2026-11-22T12:01:00Z"
        )
        val success = status(
            co.electriccoin.zcash.ui.common.model.near.SwapStatus.SUCCESS, "2026-11-22T12:02:00Z"
        )
        var requests = 0
        val swaps = mockk<SwapRepository> {
            coEvery { checkSwapStatus(any()) } answers {
                when (++requests) {
                    1 -> processing
                    2 -> success
                    3 -> processing
                    else -> throw IOException("Synthetic unavailable provider")
                }
            }
        }
        val useCase = GetSwapStatusUseCase(metadata, swaps)
        val second = mutableListOf<SwapQuoteStatusData>()
        val vm = SwapDetailVM(
            GetReloadableSwapQuoteUseCase(useCase),
            SwapDetailArgs("deposit"), mockk(), mockk(),
            CommonTransactionDetailMapper(), SwapSupportMapper(),
        )
        val firstJob = backgroundScope.launch { vm.state.collect {} }
        fun pump(condition: () -> Boolean) {
            val until = System.nanoTime() + 10_000_000_000L
            while (!condition() && System.nanoTime() < until) {
                runCurrent()
                Thread.sleep(2)
            }
            assertTrue(condition(), "The bounded production-flow probe did not reach its target state")
        }
        try {
            pump { vm.state.value?.status?.status == SwapStatus.PROCESSING }
            val secondJob = backgroundScope.launch { useCase.observe("deposit").collect { second += it } }
            pump { second.lastOrNull()?.status?.status == SwapStatus.SUCCESS }
            assertEquals(SwapStatus.SUCCESS, storedSwaps().swapIds.single().status)
            secondJob.cancel()
            testScheduler.advanceTimeBy(30_000)
            pump { vm.state.value?.showLiveDetails == false }
            val completed = checkNotNull(vm.state.value)
            assertEquals(SwapStatus.SUCCESS, completed.status.status)
            assertEquals(stringRes(R.string.private_swap_saved_status), completed.status.title)
            assertTrue(completed.infoFooter != null)
            assertNull(completed.donationBasisZec)
            testScheduler.advanceTimeBy(30_000)
            pump { requests == 4 }
            assertEquals(completed.transactionHeader, vm.state.value?.transactionHeader)
            assertEquals(SwapStatus.SUCCESS, vm.state.value?.status?.status)
            assertEquals(SwapStatus.SUCCESS, storedSwaps().swapIds.single().status)
            firstJob.cancel()
            vm.viewModelScope.cancel()
            testScheduler.advanceTimeBy(90_000)
            runCurrent()
            assertEquals(4, requests)
        } finally {
            firstJob.cancel()
            vm.viewModelScope.cancel()
            unmockkObject(FiatCurrency.USD)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aRejectedRefreshReloadsOnlyItsOriginalEntryAndWalletGeneration() = runTest {
        val ownership = WalletWriteAccess()
        val metadata = repository(metadataProvider, ownership)
        val quote = fundingQuote(SwapType.FLEX_INPUT)
        SaveORSwapUseCase(mockk(), metadata, mockk()).persistBeforeFunding(quote)
        val expected = checkNotNull(metadata.getSwapMetadata("deposit"))
        assertEquals(expected, metadata.reloadSwapMetadata(expected))
        for (other in listOf(
            expected.copy(depositAddress = "other-deposit"),
            expected.copy(provider = "other-provider"),
            expected.copy(lastUpdated = expected.lastUpdated.plusSeconds(1)),
            expected.copy(reviewedIdentity = "other-review"),
            expected.copy(mode = SwapMode.EXACT_OUTPUT),
            expected.copy(destination = swapAsset("eth", "eth")),
            expected.copy(walletWriteToken = expected.walletWriteToken!! + 1),
            expected.copy(ownerAccount = null),
        )) {
            assertNull(metadata.reloadSwapMetadata(other))
        }
        ownership.beginReset()
        assertNull(metadata.reloadSwapMetadata(expected))
        ownership.finishReset()
        ownership.replaceWallet {}
        assertNull(metadata.reloadSwapMetadata(expected))
    }

    @Test
    fun reloadMustAcknowledgeTheDirectoryBeforeReturningAnUnacknowledgedReplacement() = runTest {
        val sync = TrackingSync().apply { fail = false }
        val metadata = repository(sync.provider)
        SaveORSwapUseCase(mockk(), metadata, mockk()).persistBeforeFunding(fundingQuote(SwapType.FLEX_INPUT))
        val expected = checkNotNull(metadata.getSwapMetadata("deposit"))
        val settled = mockk<SwapQuoteStatus> {
            every { amountOutFormatted } returns BigDecimal.ONE
            every { status } returns SwapStatus.SUCCESS
            every { mode } returns SwapMode.FLEX_INPUT
        }
        sync.fail = true
        assertFalse(metadata.updateSwap(expected, settled))
        assertEquals(SwapStatus.SUCCESS, storedSwaps().swapIds.single().status)
        assertFailsWith<IOException> { metadata.reloadSwapMetadata(expected) }
        sync.fail = false
        assertEquals(SwapStatus.SUCCESS, metadata.reloadSwapMetadata(expected)?.status)
    }

    @Test
    fun aLegacySwapCanRecoverTheProviderModeWithoutChangingItsRoute() = runTest {
        assertTrue(markSwap(dataSource(), "near", SwapMode.EXACT_INPUT))
        val original = read(canonicalName)
        write(canonicalName, original.copy(accountMetadata = original.accountMetadata.copy(
            swaps = original.accountMetadata.swaps.copy(
                swapIds = original.accountMetadata.swaps.swapIds.map { it.copy(exactInput = null) },
            ),
        )))
        val metadata = repository(metadataProvider)
        val expected = checkNotNull(metadata.getSwapMetadata("deposit"))
        assertEquals(SwapMode.EXACT_INPUT, expected.mode)
        val settled = mockk<SwapQuoteStatus> {
            every { amountOutFormatted } returns BigDecimal.ONE
            every { status } returns SwapStatus.SUCCESS
            every { mode } returns SwapMode.EXACT_OUTPUT
        }
        assertTrue(metadata.updateSwap(expected, settled))
        val reloaded = checkNotNull(metadata.reloadSwapMetadata(expected))
        assertEquals(SwapStatus.SUCCESS, reloaded.status)
        assertEquals(SwapMode.EXACT_OUTPUT, reloaded.mode)
        assertEquals(expected.lastUpdated, reloaded.lastUpdated)
    }

    @Test
    fun trackingRequiresDirectoryDurabilityForCreationReplacementAndRestart() = runTest {
        var failSync = true
        var directorySyncs = 0
        val provider = MetadataProviderImpl(
            metadataEncryptor = MetadataEncryptorImpl(MetadataSerializer()),
            directorySync = DirectorySync {
                directorySyncs += 1
                if (failSync) throw java.io.IOException("Synthetic directory durability failure")
            }
        )
        fun source() = MetadataDataSourceImpl(
            metadataStorageProvider = storageProvider,
            metadataProvider = provider,
            simpleSwapAssetProvider = simpleSwapAssetProvider,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        assertFalse(markSwap(source(), provider = "creation", mode = SwapMode.EXACT_INPUT))
        assertEquals(1, directorySyncs)
        // Rename can land without a durable acknowledgement. Keep the record for recovery.
        assertEquals("creation", storedSwaps().swapIds.single().provider)
        failSync = false
        assertTrue(markSwap(source(), provider = "creation", mode = SwapMode.EXACT_INPUT))
        assertEquals(2, directorySyncs)
        failSync = true
        assertFalse(markSwap(source(), provider = "replacement", mode = SwapMode.EXACT_INPUT))
        assertEquals(3, directorySyncs)
        assertEquals("replacement", storedSwaps().swapIds.single().provider)
        failSync = false
        assertTrue(markSwap(source(), provider = "replacement", mode = SwapMode.EXACT_INPUT))
        assertEquals(4, directorySyncs)
        assertEquals("replacement", source().observe(key).first { it != null }
            ?.accountMetadata?.swaps?.swapIds?.single()?.provider)
        assertFalse(File(metadataDir, "$canonicalName.tmp").exists())
    }

    @Test
    fun readableIncomingTrackingCannotBypassFailedDirectorySync() = runTest {
        val sync = TrackingSync()
        val quote = fundingQuote(SwapType.FLEX_INPUT)
        val initial = SaveORSwapUseCase(mockk(), repository(sync.provider), mockk())
        assertFailsWith<IllegalStateException> { initial.persistBeforeFunding(quote) }
        val readable = storedSwaps().swapIds.single()
        assertEquals(SwapStatus.PENDING, readable.status)
        assertEquals(quote.reviewedIdentity(), readable.reviewedIdentity)
        assertEquals(1, sync.attempts)

        val restarted = repository(sync.provider)
        val retry = SaveORSwapUseCase(mockk(), restarted, mockk())
        assertFailsWith<IllegalStateException> { retry.persistBeforeFunding(quote) }
        assertEquals(2, sync.attempts)

        sync.fail = false
        retry.persistBeforeFunding(quote)
        assertEquals(3, sync.attempts)
        val expected = checkNotNull(restarted.getSwapMetadata("deposit"))
        val success = mockk<SwapQuoteStatus> {
            every { status } returns SwapStatus.SUCCESS
            every { mode } returns SwapMode.FLEX_INPUT
            every { amountOutFormatted } returns BigDecimal.TEN
        }
        assertTrue(restarted.updateSwap(expected, success))
        val settled = storedSwaps().swapIds.single()
        assertEquals(SwapStatus.SUCCESS, settled.status)
        retry.persistBeforeFunding(quote)
        assertEquals(5, sync.attempts)
        assertEquals(settled, storedSwaps().swapIds.single())
    }

    @Test
    fun outgoingReservationStopsOnDirectorySyncFailureAndRetainsReadableTracking() = runTest {
        val sync = TrackingSync()
        val quote = fundingQuote(SwapType.EXACT_INPUT)
        val destinationAddress = WalletAddress.Unified.new("deposit")
        val proposal = mockk<SwapTransactionProposal> {
            every { this@mockk.quote } returns quote
            every { destination } returns destinationAddress
            every { totalFees } returns Zatoshi(0)
            every { totalFeesUsd } returns BigDecimal.ZERO
        }
        val initial = ProcessSwapTransactionUseCase(repository(sync.provider), mockk())
        assertFailsWith<IllegalStateException> { initial.reserveFunding(proposal) {} }
        assertEquals(1, sync.attempts)
        val pending = storedSwaps().swapIds.single()
        assertEquals(SwapStatus.PENDING, pending.status)
        assertEquals(quote.reviewedIdentity(), pending.reviewedIdentity)

        sync.fail = false
        val restarted = ProcessSwapTransactionUseCase(repository(sync.provider), mockk())
        assertFailsWith<IllegalStateException> { restarted.reserveFunding(proposal) {} }
        assertEquals(1, sync.attempts)
        assertEquals(pending, storedSwaps().swapIds.single())
    }

    @Test
    fun aLegacyFileUnderALaterKeyIsMergedIntoTheCanonicalFileAndDeleted() =
        runTest {
            write(canonicalName, metadata(lastUpdated = 1, annotations = listOf(note("tx1", "canonical", 1))))
            write(legacyName, metadata(lastUpdated = 2, bookmarked = listOf(bookmark("tx2", 2))), metadataKey(1))

            val result = dataSource().observe(key).first { it != null }

            val expected =
                metadata(
                    lastUpdated = 2,
                    annotations = listOf(note("tx1", "canonical", 1)),
                    bookmarked = listOf(bookmark("tx2", 2))
                )
            assertEquals(expected, result)
            assertEquals(listOf(canonicalName), fileNames())
            assertEquals(expected, read(canonicalName))
            assertEquals(listOf(File(metadataDir, canonicalName)), storageProvider.getStorageFiles(key))
            assertEquals(expected, dataSource().observe(key).first { it != null })
        }

    @Test
    fun anUndecodableCanonicalFileIsSetAsideThenAFreshOneIsWrittenAndTheOldOneMergedBack() =
        runTest {
            val otherKey = metadataKey(9)
            write(canonicalName, metadata(lastUpdated = 1, read = listOf("old")), otherKey)
            val dataSource = dataSource()
            val emitted = collectEmissions(dataSource, key)

            assertEquals(listOf(emptyList()), emitted.map { it?.accountMetadata?.read })
            val setAsideName = fileNames().single()
            assertTrue(setAsideName.startsWith("$canonicalName.undecodable-"))

            dataSource.markTxMemoAsRead("new", key)
            runCurrent()

            assertEquals(listOf(listOf("new")), emitted.drop(1).map { it?.accountMetadata?.read })
            assertEquals(listOf(canonicalName, setAsideName), fileNames())
            assertEquals(listOf("new"), read(canonicalName).accountMetadata.read)

            val keyThatDecodesTheOldFile = metadataKey(0, 1, 9)
            val merged = dataSource.observe(keyThatDecodesTheOldFile).first { it != null }

            assertEquals(setOf("old", "new"), merged?.accountMetadata?.read?.toSet())
            assertEquals(listOf(canonicalName), fileNames())
        }

    @Test
    fun deleteWipesEveryOwnedFileAndEmitsNullToEveryAccount() =
        runTest {
            write(canonicalName, metadata(lastUpdated = 1))
            val dataSource = dataSource()
            val emitted = collectEmissions(dataSource, key)
            val removedKey = metadataKey(9)
            write(removedKey.fileIdentifier(), metadata(lastUpdated = 1), removedKey)
            val removedEmissions = collectEmissions(dataSource, removedKey)
            write(legacyName, metadata(lastUpdated = 1), metadataKey(1))
            File(metadataDir, "$canonicalName.undecodable-5").writeText("aside")
            File(metadataDir, "$canonicalName.tmp").writeText("partial")
            File(metadataDir, "unrelated").writeText("other")

            dataSource.delete()
            runCurrent()

            assertEquals(listOf("unrelated"), fileNames())
            assertNull(emitted.last())
            assertNull(removedEmissions.last())
        }

    @Test
    fun flippingABookmarkTwiceKeepsAnUnbookmarkedEntry() =
        runTest {
            val dataSource = dataSource()

            dataSource.flipTxAsBookmarked("tx1", key)
            assertEquals(listOf(true), read(canonicalName).accountMetadata.bookmarked.map { it.isBookmarked })

            dataSource.flipTxAsBookmarked("tx1", key)
            val bookmarks = read(canonicalName).accountMetadata.bookmarked
            assertEquals(listOf("tx1" to false), bookmarks.map { it.txId to it.isBookmarked })
        }

    @Test
    fun aNoteIsCreatedUpdatedAndDeletedInPlace() =
        runTest {
            val dataSource = dataSource()

            dataSource.createOrUpdateTxNote("tx1", "first", key)
            dataSource.createOrUpdateTxNote("tx1", "second", key)
            assertEquals(listOf("tx1" to "second"), notes())

            dataSource.deleteTxNote("tx1", key)
            assertEquals(listOf("tx1" to null), notes())
        }

    @Test
    fun markingAMemoAsReadTwiceKeepsOneEntry() =
        runTest {
            val dataSource = dataSource()

            dataSource.markTxMemoAsRead("tx1", key)
            dataSource.markTxMemoAsRead("tx1", key)

            assertEquals(listOf("tx1"), read(canonicalName).accountMetadata.read)
        }

    @Test
    fun aSwapIsAddedReplacedAndUpdatedByDepositAddress() =
        runTest {
            val dataSource = dataSource()

            markSwap(dataSource, provider = "first", mode = SwapMode.EXACT_OUTPUT)
            assertEquals(
                listOf(false),
                storedSwaps().swapIds.map { it.exactInput }
            )
            markSwap(dataSource, provider = "second", mode = SwapMode.EXACT_INPUT)
            val markedAt = storedSwaps().swapIds.single().lastUpdated
            dataSource.updateSwap(
                depositAddress = "deposit",
                amountOutFormatted = BigDecimal.TEN,
                status = SwapStatus.SUCCESS,
                mode = SwapMode.EXACT_OUTPUT,
                origin = swapAsset("zec", "zec"),
                destination = swapAsset("btc", "btc"),
                key = key
            )
            dataSource.updateSwap(
                depositAddress = "unknown",
                amountOutFormatted = BigDecimal.ONE,
                status = SwapStatus.FAILED,
                mode = SwapMode.EXACT_INPUT,
                origin = swapAsset("zec", "zec"),
                destination = swapAsset("btc", "btc"),
                key = key
            )

            val swap = storedSwaps().swapIds.single()
            assertEquals(markedAt, swap.lastUpdated)
            assertEquals("deposit", swap.depositAddress)
            assertEquals("second", swap.provider)
            assertEquals(SwapStatus.SUCCESS, swap.status)
            assertEquals(BigDecimal.TEN, swap.amountOutFormatted)
            assertEquals(false, swap.exactInput)
            assertEquals("btc", swap.toAsset.token)
        }

    @Test
    fun restartedStorageKeepsReviewedIdentityAndRejectsStaleStatus() = runTest {
        val reviewedIdentity = "synthetic-reviewed-identity"
        val initial = dataSource()
        assertTrue(markSwap(initial, "near", SwapMode.EXACT_INPUT, reviewedIdentity))
        val createdAt = storedSwaps().swapIds.single().lastUpdated

        suspend fun refresh(
            source: MetadataDataSourceImpl,
            status: SwapStatus,
            updatedAt: String,
            amount: BigDecimal,
        ) = source.updateSwap(
            depositAddress = "deposit",
            amountOutFormatted = amount,
            status = status,
            mode = SwapMode.EXACT_OUTPUT,
            origin = swapAsset("zec", "zec"),
            destination = swapAsset("usdc", "near"),
            key = key,
            statusUpdatedAt = kotlin.time.Instant.parse(updatedAt),
            expectedCreatedAt = createdAt,
            expectedReviewedIdentity = reviewedIdentity,
        )

        assertTrue(refresh(initial, SwapStatus.FAILED, "2026-11-22T00:00:00.0001Z", BigDecimal.ONE))
        assertTrue(refresh(initial, SwapStatus.PROCESSING, "2026-11-22T00:00:00.0002Z", BigDecimal.ONE))
        val restarted = dataSource()
        assertFalse(refresh(restarted, SwapStatus.FAILED, "2026-11-22T00:00:00.0001Z", BigDecimal.ONE))
        assertTrue(refresh(restarted, SwapStatus.SUCCESS, "2026-11-22T00:00:00.0003Z", BigDecimal.TEN))
        assertFalse(refresh(restarted, SwapStatus.SUCCESS, "2026-11-22T00:00:00.0002Z", BigDecimal.ONE))
        assertFalse(refresh(restarted, SwapStatus.PROCESSING, "2026-11-22T00:00:01Z", BigDecimal.ONE))
        val saved = storedSwaps().swapIds.single()
        assertEquals(SwapStatus.SUCCESS, saved.status)
        assertEquals(BigDecimal.TEN, saved.amountOutFormatted)
        assertEquals(createdAt, saved.lastUpdated)
        assertEquals(kotlin.time.Instant.parse("2026-11-22T00:00:00.0003Z"), saved.statusUpdatedAt)
        assertEquals(reviewedIdentity, saved.reviewedIdentity)
        assertEquals(true, saved.exactInput)
        assertFalse(markSwap(restarted, "near", SwapMode.EXACT_INPUT, "another-reviewed-identity"))
        assertTrue(markSwap(restarted, "near", SwapMode.EXACT_INPUT, reviewedIdentity))
        assertEquals(saved, storedSwaps().swapIds.single())
    }

    @Test
    fun theSwapAssetHistoryPutsTheLatestFirstAndKeepsTen() =
        runTest {
            val dataSource = dataSource()

            (0..10).forEach { dataSource.addSwapAssetToHistory("token$it", "chain", key) }
            dataSource.addSwapAssetToHistory("token5", "chain", key)

            val expected = listOf(5, 10, 9, 8, 7, 6, 4, 3, 2, 1).map { "token$it:chain" }
            assertEquals(expected, storedSwaps().lastUsedAssetHistory.toList())
        }

    @Test
    fun anObserverIgnoresUpdatesForOtherKeys() =
        runTest {
            val sameSizeKey = metadataKey(2, 3)
            val shorterKey = metadataKey(4)
            val dataSource = dataSource()
            val emitted = collectEmissions(dataSource, key)

            dataSource.markTxMemoAsRead("other", sameSizeKey)
            dataSource.markTxMemoAsRead("shorter", shorterKey)
            dataSource.markTxMemoAsRead("own", key)
            runCurrent()

            assertEquals(listOf(emptyList(), listOf("own")), emitted.map { it?.accountMetadata?.read })
        }

    @Test
    fun aFailedReadNeverDeletesTheFile() =
        runTest {
            File(metadataDir.apply { mkdirs() }, legacyName).writeText("not encrypted metadata")

            dataSource().observe(key).first { it != null }

            assertEquals("not encrypted metadata", File(metadataDir, legacyName).readText())
            assertFalse(File(metadataDir, canonicalName).length() > 0)
        }

    private fun TestScope.dataSource(
        provider: MetadataProviderImpl = metadataProvider,
        ownership: WalletWriteAccess = WalletWriteAccess(),
    ) =
        MetadataDataSourceImpl(
            metadataStorageProvider = storageProvider,
            metadataProvider = provider,
            simpleSwapAssetProvider = simpleSwapAssetProvider,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            walletWriteAccess = ownership
        )

    private fun TestScope.repository(
        provider: MetadataProviderImpl,
        ownership: WalletWriteAccess = WalletWriteAccess(),
    ): MetadataRepositoryImpl {
        val uuid = AccountUuid.new(ByteArray(16))
        val wallet = mockk<ZodlAccount> { every { sdkAccount } returns Account.new(uuid) }
        val accounts = mockk<AccountDataSource> {
            every { selectedAccount } returns MutableStateFlow<WalletAccount?>(wallet)
            every { allAccounts } returns MutableStateFlow<List<WalletAccount>?>(listOf(wallet))
            coEvery { getSelectedAccount() } returns wallet
        }
        return MetadataRepositoryImpl(
            accountDataSource = accounts,
            metadataDataSource = dataSource(provider, ownership),
            metadataKeyStorageProvider = mockk<MetadataKeyStorageProvider> { coEvery { get(uuid) } returns key },
            persistableWalletProvider = mockk(),
            simpleSwapAssetProvider = simpleSwapAssetProvider,
            walletWriteAccess = ownership
        )
    }

    private fun fundingQuote(mode: SwapType): NearSwapQuote {
        val btc = SwapCatalogTestFixture.externalAssets.single { it.assetId == "nep141:btc.omft.near" }
        val zec = SwapCatalogTestFixture.nativeZec
        val (origin, destination) = if (mode == SwapType.FLEX_INPUT) btc to zec else zec to btc
        val response = Json.decodeFromString<QuoteResponseDto>(
            """
            {
                "timestamp": "2026-11-22T12:00:00Z",
                "quoteRequest": {
                    "dry": false, "swapType": "${mode.apiValue}", "slippageTolerance": 100,
                    "originAsset": "${origin.assetId}", "destinationAsset": "${destination.assetId}",
                    "depositType": "ORIGIN_CHAIN", "amount": "100000000", "refundTo": "refund",
                    "refundType": "ORIGIN_CHAIN", "recipient": "recipient", "recipientType": "DESTINATION_CHAIN",
                    "deadline": "2026-11-22T13:00:00Z", "appFees": []
                },
                "quote": {
                    "depositAddress": "deposit", "amountIn": "100000000", "amountInFormatted": "1",
                    "amountInUsd": "100", "minAmountIn": "99000000", "amountOut": "100000000",
                    "amountOutFormatted": "1", "amountOutUsd": "99", "minAmountOut": "99000000",
                    "deadline": "2026-11-22T13:00:00Z"
                }
            }
            """.trimIndent()
        )
        return NearSwapQuote(
            response = response,
            originAsset = origin,
            destinationAsset = destination,
            depositAddress = DynamicSwapAddress("deposit"),
            destinationAddress = DynamicSwapAddress("recipient"),
            refundAddress = DynamicSwapAddress("refund"),
            expectedSlippageToleranceBps = 100,
        )
    }

    private class TrackingSync {
        var fail = true
        var attempts = 0
        val provider = MetadataProviderImpl(
            metadataEncryptor = MetadataEncryptorImpl(MetadataSerializer()),
            directorySync = {
                attempts++
                if (fail) throw IOException("Synthetic metadata directory sync failure")
            }
        )
    }

    /**
     * Collects everything [dataSource] emits for [key] after the initial null and runs the
     * collector until it waits for updates.
     */
    private fun TestScope.collectEmissions(dataSource: MetadataDataSourceImpl, key: MetadataKey): List<MetadataV3?> {
        val emitted = mutableListOf<MetadataV3?>()
        backgroundScope.launch {
            dataSource.observe(key).drop(1).collect { emitted += it }
        }
        runCurrent()
        return emitted
    }

    private suspend fun markSwap(
        dataSource: MetadataDataSourceImpl,
        provider: String,
        mode: SwapMode,
        reviewedIdentity: String? = null,
    ) =
        dataSource.markTxAsSwap(
            depositAddress = "deposit",
            provider = provider,
            origin = swapAsset("zec", "zec"),
            destination = swapAsset("usdc", "near"),
            totalFees = Zatoshi(1),
            totalFeesUsd = BigDecimal.ONE,
            mode = mode,
            amountOutFormatted = BigDecimal.ONE,
            status = SwapStatus.PENDING,
            key = key,
            reviewedIdentity = reviewedIdentity,
        )

    private fun storedSwaps() = read(canonicalName).accountMetadata.swaps

    private fun notes() = read(canonicalName).accountMetadata.annotations.map { it.txId to it.content }

    private fun fileNames() = metadataDir.list().orEmpty().sorted()

    private fun write(name: String, metadata: MetadataV3, writeKey: MetadataKey = key) {
        metadataDir.mkdirs()
        metadataProvider.writeMetadataToFile(File(metadataDir, name), metadata, writeKey)
    }

    private fun read(name: String) = metadataProvider.readMetadataFromFile(File(metadataDir, name), key)

    private fun swapAsset(token: String, chain: String) =
        swapAssets.getOrPut("$token:$chain") {
            SwapAssetTestFixture.simpleAsset(token, chain)
        }

    private fun metadata(
        lastUpdated: Long,
        bookmarked: List<BookmarkMetadataV3> = emptyList(),
        read: List<String> = emptyList(),
        annotations: List<AnnotationMetadataV3> = emptyList(),
    ) = MetadataV3(
        lastUpdated = Instant.ofEpochSecond(lastUpdated),
        accountMetadata =
            AccountMetadataV3(
                bookmarked = bookmarked,
                read = read,
                annotations = annotations,
                swaps = SwapsMetadataV3(swapIds = emptyList(), lastUsedAssetHistory = emptySet())
            )
    )

    private fun note(txId: String, content: String, lastUpdated: Long) =
        AnnotationMetadataV3(txId = txId, content = content, lastUpdated = Instant.ofEpochSecond(lastUpdated))

    private fun bookmark(txId: String, lastUpdated: Long) =
        BookmarkMetadataV3(txId = txId, lastUpdated = Instant.ofEpochSecond(lastUpdated), isBookmarked = true)

    private fun metadataKey(vararg seeds: Int) =
        MetadataKey(
            bytes =
                seeds.map { seed ->
                    SecretBytes.copyFrom(ByteArray(SEED_SIZE) { (it + seed).toByte() }, InsecureSecretKeyAccess.get())
                }
        )

    private companion object {
        const val SEED_SIZE = 32
    }
}
