package co.electriccoin.zcash.ui.screen.swap.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.near.NearSwapAsset
import co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProviderImpl
import co.electriccoin.zcash.ui.common.repository.SwapRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.EnsureSwapAssetsLoadedUseCase
import co.electriccoin.zcash.ui.common.usecase.FilterSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.FilterSwapBlockchainsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetCuratedSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectSwapBlockchainUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapAssetPickerUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import co.electriccoin.zcash.ui.design.R as UIR

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwapPickerVMTest {
    private val context = RuntimeEnvironment.getApplication()
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private val dataSource = mockk<SwapDataSource>()
    private val viewModels = mutableListOf<ViewModel>()
    private val catalog = SwapCatalogTestFixture
    private val simpleAssets = SimpleSwapAssetProviderImpl(catalog.tokenIcons, catalog.tokenNames, catalog.blockchains)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun assetRetryShowsDistinctRowsForMatchingLabelsAndReturnsThePickedId() = runVmTest {
        val sharedNetworkLabel = stringRes("Shared network")
        val eth = asset("USDC", "eth").let { it.copy(blockchain = it.blockchain.copy(chainName = sharedNetworkLabel)) }
        val sol = asset("USDC", "sol").let { it.copy(blockchain = it.blockchain.copy(chainName = sharedNetworkLabel)) }
        val unrelated = sol.copy(assetId = "test:unrecognized-usdc")
        val recovered = CompletableDeferred<List<SwapAsset>>()
        val repository = failedCatalog(recovered)
        val picker = NavigateToSwapAssetPickerUseCase(navigation)
        val selection = async { picker() }
        runCurrent()
        val args = slot<SwapAssetPickerArgs>()
        verify(exactly = 1) { navigation.forward(capture(args)) }
        val vm = SwapAssetPickerVM(
            args = args.captured,
            getSwapAssets = GetCuratedSwapAssetsUseCase(repository, simpleAssets),
            metadataRepository = mockk { every { observeLastUsedAssetHistory() } returns flowOf(null) },
            navigateToSwapAssetPicker = picker,
            filterSwapAssets = FilterSwapAssetsUseCase(context),
            swapRepository = repository,
        ).also { viewModels += it }

        val error = assertIs<SwapAssetPickerDataState.Error>(vm.state.first { it != null }?.data)
        assertEquals(stringRes(UIR.string.disconnectHWWallet_tryAgain), error.buttonState.text)
        error.buttonState.onClick()
        vm.state.first { it?.data is SwapAssetPickerDataState.Loading }
        recovered.complete(listOf(catalog.nativeZec, unrelated, eth, sol))

        val state = assertNotNull(vm.state.first { it?.data is SwapAssetPickerDataState.Success })
        state.search.onValueChange("USDC")
        val searched = vm.state.first { it?.search?.value == stringRes("USDC") }
        val rows = assertIs<SwapAssetPickerDataState.Success>(searched?.data).items
        assertEquals(listOf(stringRes("USDC"), stringRes("USDC")), rows.map { it.title })
        assertEquals(listOf(sharedNetworkLabel, sharedNetworkLabel), rows.map { it.subtitle })
        assertEquals(setOf("eth_USDC_${eth.assetId}", "sol_USDC_${sol.assetId}"), rows.map { it.key }.toSet())
        assertNotNull(rows.single { it.key == "sol_USDC_${sol.assetId}" }.onClick).invoke()

        assertSame(sol, selection.await())
        coVerify(exactly = 2) { dataSource.getSupportedTokens() }
        verify(exactly = 1) { navigation.back() }
    }

    @Test
    fun networkPickerOffersOfflineChoicesDuringAutomaticRetryAndReturnsTheRecoveredNetwork() = runVmTest {
        val eth = asset("ETH", "eth")
        val usdc = asset("USDC", "eth")
        val sol = asset("SOL", "sol")
        val recovered = CompletableDeferred<List<SwapAsset>>()
        val repository = failedCatalog(recovered)
        val picker = NavigateToSelectSwapBlockchainUseCase(navigation)
        val selection = async { picker() }
        runCurrent()
        val args = slot<SwapBlockchainPickerArgs>()
        verify(exactly = 1) { navigation.forward(capture(args)) }
        val vm = SwapBlockchainPickerVM(
            getCuratedSwapAssets = GetCuratedSwapAssetsUseCase(repository, simpleAssets),
            args = args.captured,
            navigateToSelectSwapBlockchain = picker,
            filterSwapBlockchains = FilterSwapBlockchainsUseCase(context, simpleAssets),
            swapRepository = repository,
            ensureSwapAssetsLoaded = EnsureSwapAssetsLoadedUseCase(repository),
        ).also { viewModels += it }

        val fallback = assertIs<SwapAssetPickerDataState.Success>(vm.state.value.data).items
        assertTrue(fallback.any { it.key == "btc" })
        assertTrue(fallback.none { it.key == "zec" })
        runCurrent()
        assertTrue(repository.assets.value.isLoading)
        coVerify(exactly = 2) { dataSource.getSupportedTokens() }
        recovered.complete(listOf(catalog.nativeZec, eth, usdc, sol))

        val recoveredState = vm.state.first {
            (it.data as? SwapAssetPickerDataState.Success)?.items?.map { row -> row.key } == listOf("eth", "sol")
        }
        val rows = assertIs<SwapAssetPickerDataState.Success>(recoveredState.data).items
        val row = rows.single { it.key == "sol" }
        assertEquals(sol.chainName, row.title)
        assertNotNull(row.onClick).invoke()

        assertSame(sol.blockchain, selection.await())
        verify(exactly = 1) { navigation.back() }
    }

    private fun asset(symbol: String, chain: String): NearSwapAsset =
        catalog.externalAssets.single { it.tokenTicker == symbol && it.chainTicker == chain }

    private suspend fun TestScope.failedCatalog(recovery: CompletableDeferred<List<SwapAsset>>): SwapRepositoryImpl {
        var requests = 0
        coEvery { dataSource.getSupportedTokens() } coAnswers {
            if (requests++ == 0) throw IOException("Offline test catalog")
            recovery.await()
        }
        val repository = SwapRepositoryImpl(
            swapDataSource = dataSource,
            swapAssetCacheProvider = mockk(relaxed = true) { coEvery { get() } returns emptyList() },
        ).also { it.scope = backgroundScope }
        repository.requestRefreshAssetsOnce()
        assertNull(repository.assets.value.data)
        assertIs<IOException>(repository.assets.value.error)
        return repository
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            val jobs = viewModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
        }
    }
}
