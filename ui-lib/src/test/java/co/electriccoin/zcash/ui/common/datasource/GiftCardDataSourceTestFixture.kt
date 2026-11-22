package co.electriccoin.zcash.ui.common.datasource

import android.app.Application
import cash.z.ecc.android.sdk.GiftCardRedeemer
import cash.z.ecc.android.sdk.GiftCardRedeemers
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.ADDRESS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.ALIAS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.AMOUNT
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.BIRTHDAY
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.CARD_ID
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.ENDPOINT
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.LINK
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.MEMO_LABEL
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.MESSAGE
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import cash.z.ecc.android.sdk.model.GiftCardOrigin as SdkGiftCardOrigin

abstract class GiftCardDataSourceTestFixture {
    private val application =
        mockk<Application>(relaxed = true) {
            every { getString(R.string.redeemGift_memo) } returns MEMO_LABEL
        }

    protected val persistableWalletProvider = mockk<PersistableWalletProvider>()

    protected val mainSynchronizer = mockk<Synchronizer>()

    private val synchronizerProvider =
        mockk<SynchronizerProvider> {
            every { synchronizer } returns MutableStateFlow(mainSynchronizer)
        }

    protected val isTorEnabledStorageProvider = mockk<IsTorEnabledStorageProvider>()

    protected val redeemer = mockk<GiftCardRedeemer>()

    protected val replacementRedeemer = mockk<GiftCardRedeemer>()

    protected val recipient = mockk<RecipientAddress>()

    protected var newRedeemerCalls = 0
        private set

    protected val parsedCard by lazy { card(ZcashNetwork.Mainnet) }

    @BeforeTest
    fun setUp() {
        mockkObject(GiftCard.Companion)
        mockkObject(GiftCardRedeemers)
        mockkObject(RecipientAddress.Companion)

        coEvery { persistableWalletProvider.getPersistableWallet() } returns wallet(ZcashNetwork.Mainnet)
        coEvery { GiftCard.parse(LINK) } returns parsedCard
        coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Mainnet) } returns emptySet()
        coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Testnet) } returns emptySet()
        coEvery { isTorEnabledStorageProvider.get() } returns true
        every { GiftCardRedeemers.new(any(), any(), any(), any(), any(), any()) } answers {
            listOf(redeemer, replacementRedeemer)[newRedeemerCalls++]
        }
        listOf(redeemer, replacementRedeemer).forEach {
            every { it.alias } returns ALIAS
            coEvery { it.close() } returns Unit
        }
        coEvery { RecipientAddress.new(ADDRESS, ZcashNetwork.Mainnet) } returns recipient
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    protected fun dataSource(scope: TestScope? = null) =
        GiftCardDataSourceImpl(
            application = application,
            persistableWalletProvider = persistableWalletProvider,
            synchronizerProvider = synchronizerProvider,
            isTorEnabledStorageProvider = isTorEnabledStorageProvider,
        ).also { dataSource -> scope?.let { dataSource.scope = it } }

    private fun wallet(network: ZcashNetwork) =
        mockk<PersistableWallet> {
            every { this@mockk.network } returns network
            every { endpoint } returns ENDPOINT
        }

    protected fun card(
        network: ZcashNetwork,
        message: String? = MESSAGE,
        origin: SdkGiftCardOrigin = SdkGiftCardOrigin.LegacyV2
    ) = mockk<GiftCard>(relaxUnitFun = true) {
        every { this@mockk.network } returns network
        every { id } returns CARD_ID
        every { this@mockk.origin } returns origin
        every { birthdayHeight } returns BlockHeight.new(BIRTHDAY)
        every { statedAmount } returns Zatoshi(AMOUNT)
        every { description } returns message
    }

    protected fun success(txId: FirstClassByteArray) = TransactionSubmitResult.Success(txId)
}

internal object GiftCardDataSourceTestValues {
    const val LINK = "https://gift.zodl.com/#v=1&key=zgift1test&height=3100000"
    const val OTHER_LINK = "https://gift.zodl.com/#v=1&key=zgift1other&height=3100000"
    const val CARD_ID = "0123456789abcdef0123456789abcdef"
    const val ALIAS = "giftcard_$CARD_ID"
    const val OTHER_ALIAS = "giftcard_fedcba9876543210fedcba9876543210"
    const val ADDRESS = "u1destination"
    const val BIRTHDAY = 3_100_000L
    const val AMOUNT = 10_000_000L
    const val FEE = 10_000L
    const val MESSAGE = "Welcome to Zcash Summit"
    const val MEMO_LABEL = "Gift Card"
    const val CLOSE_DURATION_MS = 1_000L
    val ENDPOINT = LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true)
    val TX_ID = FirstClassByteArray(ByteArray(32) { it.toByte() })
}
