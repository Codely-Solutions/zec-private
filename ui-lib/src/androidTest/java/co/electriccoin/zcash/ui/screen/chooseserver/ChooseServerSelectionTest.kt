package co.electriccoin.zcash.ui.screen.chooseserver

import android.app.Application
import androidx.navigation.NavBackStackEntry
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.type.ServerValidation
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.component.EndpointTextFieldInnerState
import co.electriccoin.zcash.ui.common.component.ZashiEndpointTextFieldParser
import co.electriccoin.zcash.ui.common.model.FastestServersState
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletServerSelection
import co.electriccoin.zcash.ui.common.provider.WalletServerSelectionProvider
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.usecase.GetAutomaticEndpointUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedEndpointUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveFastestServersUseCase
import co.electriccoin.zcash.ui.common.usecase.PersistServerSelectionUseCase
import co.electriccoin.zcash.ui.common.usecase.RefreshFastestServersUseCase
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.design.component.InnerTextFieldState
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.fixture.MockSynchronizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.reflect.KClass
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Ignore("Bit-rotted fakes; re-enable after stubbing new ZashiProposalRepository / KeystoneProposalRepository deps")
class ChooseServerSelectionTest {
    @Test
    @SmallTest
    fun automaticToManualSavePinsCurrentEndpointWithoutEndpointTap() =
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<Application>()
            val lightWalletEndpointProvider = LightWalletEndpointProvider(application)
            val currentEndpoint = lightWalletEndpointProvider.getEndpoints().last()
            val persistableWalletProvider = FakePersistableWalletProvider(currentEndpoint)
            val walletRepository = FakeWalletRepository(currentEndpoint)
            val viewModel =
                createViewModel(
                    application,
                    lightWalletEndpointProvider,
                    persistableWalletProvider,
                    walletRepository
                )

            val initialState =
                withTimeout(STATE_TIMEOUT_MILLIS) {
                    viewModel.state.filterNotNull().first()
                }
            assertFalse(initialState.connectionMode.isManualSelected)

            initialState.connectionMode.manual.onClick()

            val manualState =
                withTimeout(STATE_TIMEOUT_MILLIS) {
                    viewModel.state
                        .filterNotNull()
                        .first { it.connectionMode.isManualSelected && it.saveButton.isEnabled }
                }
            assertTrue(
                manualState.fastest.servers.any {
                    it.radioButtonState.isChecked && it.badge != null
                }
            )

            manualState.saveButton.onClick()

            withTimeout(STATE_TIMEOUT_MILLIS) {
                persistableWalletProvider.serverSelection.first { it.isAutomatic == false }
            }
            assertEquals(false, persistableWalletProvider.getServerSelection().isAutomatic)
            assertEquals(currentEndpoint, persistableWalletProvider.getServerSelection().endpoint)
        }

    @Test
    @SmallTest
    fun savingServerSelectionDisablesAndIgnoresSelectionChanges() =
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<Application>()
            val lightWalletEndpointProvider = LightWalletEndpointProvider(application)
            val currentEndpoint = lightWalletEndpointProvider.getEndpoints().last()
            val persistableWalletProvider = FakePersistableWalletProvider(currentEndpoint)
            val continueEndpointUpdate = CompletableDeferred<Unit>()
            persistableWalletProvider.continueEndpointUpdate = continueEndpointUpdate
            val walletRepository = FakeWalletRepository(currentEndpoint)
            val viewModel =
                createViewModel(
                    application,
                    lightWalletEndpointProvider,
                    persistableWalletProvider,
                    walletRepository
                )

            try {
                val initialState =
                    withTimeout(STATE_TIMEOUT_MILLIS) {
                        viewModel.state.filterNotNull().first()
                    }
                assertFalse(initialState.connectionMode.isManualSelected)

                initialState.connectionMode.manual.onClick()

                val manualState =
                    withTimeout(STATE_TIMEOUT_MILLIS) {
                        viewModel.state
                            .filterNotNull()
                            .first { it.connectionMode.isManualSelected && it.saveButton.isEnabled }
                    }

                manualState.saveButton.onClick()
                withTimeout(STATE_TIMEOUT_MILLIS) {
                    persistableWalletProvider.endpointUpdateStarted.await()
                }

                val savingState =
                    withTimeout(STATE_TIMEOUT_MILLIS) {
                        viewModel.state
                            .filterNotNull()
                            .first { it.saveButton.isLoading }
                    }

                assertFalse(savingState.fastest.retryButton.isEnabled)
                val customServer = savingState.customServer()
                assertFalse(customServer.newServerTextFieldState.isEnabled)
                assertFalse(customServer.isExpanded)

                savingState.connectionMode.automatic.onClick()
                savingState.fastest.retryButton.onClick()
                savingState.defaultServer().radioButtonState.onClick()
                customServer.radioButtonState.onClick()
                customServer.newServerTextFieldState.onValueChange(
                    EndpointTextFieldInnerState(
                        innerTextFieldState = InnerTextFieldState(value = stringRes("custom.example.com:443")),
                        endpoint = ZashiEndpointTextFieldParser.toEndpointOrNull("custom.example.com:443"),
                        lastValidEndpoint = ZashiEndpointTextFieldParser.toEndpointOrNull("custom.example.com:443"),
                    )
                )

                val stillSavingState =
                    withTimeout(STATE_TIMEOUT_MILLIS) {
                        viewModel.state
                            .filterNotNull()
                            .first { it.saveButton.isLoading }
                    }

                assertTrue(stillSavingState.connectionMode.isManualSelected)
                assertEquals(0, walletRepository.refreshCount)

                val stillCustomServer = stillSavingState.customServer()
                assertFalse(stillCustomServer.isExpanded)
                assertEquals(
                    "",
                    stillCustomServer.newServerTextFieldState.innerState.innerTextFieldState.value
                        .getString(application)
                )
            } finally {
                continueEndpointUpdate.complete(Unit)
            }

            val completedState =
                withTimeout(STATE_TIMEOUT_MILLIS) {
                    viewModel.state
                        .filterNotNull()
                        .first { it.connectionMode.isManualSelected && !it.saveButton.isLoading }
                }
            assertTrue(completedState.connectionMode.isManualSelected)
            assertEquals(currentEndpoint, persistableWalletProvider.getServerSelection().endpoint)
        }

    companion object {
        private const val STATE_TIMEOUT_MILLIS = 2_000L
    }
}

private fun createViewModel(
    application: Application,
    lightWalletEndpointProvider: LightWalletEndpointProvider,
    persistableWalletProvider: FakePersistableWalletProvider,
    walletRepository: WalletRepository,
): ChooseServerVM {
    val getSelectedEndpoint = GetSelectedEndpointUseCase(persistableWalletProvider)
    val getAutomaticEndpoint =
        GetAutomaticEndpointUseCase(
            walletRepository = walletRepository,
            lightWalletEndpointProvider = lightWalletEndpointProvider,
            getSelectedEndpoint = getSelectedEndpoint,
        )
    return ChooseServerVM(
        application = application,
        observeFastestServers = ObserveFastestServersUseCase(walletRepository),
        walletServerSelectionProvider = persistableWalletProvider,
        lightWalletEndpointProvider = lightWalletEndpointProvider,
        refreshFastestServersUseCase = RefreshFastestServersUseCase(walletRepository),
        persistServerSelection =
            PersistServerSelectionUseCase(
                application = application,
                walletServerSelectionProvider = persistableWalletProvider,
                synchronizerProvider = FakeSynchronizerProvider(),
                getAutomaticEndpoint = getAutomaticEndpoint,
            ),
        navigationRouter = FakeNavigationRouter,
        getAutomaticEndpoint = getAutomaticEndpoint
    )
}

private fun ChooseServerState.customServer() = other.servers.filterIsInstance<ServerState.Custom>().first()

private fun ChooseServerState.defaultServer() = other.servers.filterIsInstance<ServerState.Default>().first()

private class FakePersistableWalletProvider(
    endpoint: LightWalletEndpoint
) : PersistableWalletProvider, WalletServerSelectionProvider {
    override val writeVersion = 0L
    override val serverSelection = MutableStateFlow(WalletServerSelection(endpoint, null))
    val endpointUpdateStarted = CompletableDeferred<Unit>()
    var continueEndpointUpdate: CompletableDeferred<Unit>? = null

    override suspend fun getServerSelection() = serverSelection.value

    override suspend fun storeServerSelection(
        selection: WalletServerSelection,
        version: Long,
        expected: WalletServerSelection?,
    ): Boolean {
        endpointUpdateStarted.complete(Unit)
        continueEndpointUpdate?.await()
        serverSelection.value = selection
        return true
    }

    private val mutablePersistableWallet =
        MutableStateFlow(
            PersistableWallet(
                network = ZcashNetwork.Mainnet,
                endpoint = endpoint,
                birthday = null,
                seedPhrase = SeedPhrase(List(SeedPhrase.SEED_PHRASE_SIZE) { "abandon" }),
                walletInitMode = WalletInitMode.ExistingWallet
            )
        )

    override val persistableWallet: Flow<PersistableWallet?> = mutablePersistableWallet

    override suspend fun store(persistableWallet: PersistableWallet) {
        mutablePersistableWallet.value = persistableWallet
    }

    override suspend fun storeIfCurrent(
        expected: PersistableWallet,
        replacement: PersistableWallet,
        version: Long,
    ): Boolean = error("This server selection fixture does not run seed-mismatch recovery")

    override suspend fun getPersistableWallet() = mutablePersistableWallet.value

    override suspend fun requirePersistableWallet() = checkNotNull(mutablePersistableWallet.value)
}

private class FakeWalletRepository(
    fastestEndpoint: LightWalletEndpoint,
) : WalletRepository {
    override val walletInitialization =
        MutableStateFlow(co.electriccoin.zcash.ui.common.repository.WalletInitializationState.IDLE)
    override val secretState = MutableStateFlow(SecretState.NONE)
    override val fastestEndpoints = MutableStateFlow(FastestServersState(listOf(fastestEndpoint), false))
    override val walletRestoringState = MutableStateFlow(WalletRestoringState.NONE)
    override val isIronwoodAnnouncementShown = MutableStateFlow<Boolean?>(true)

    var refreshCount = 0
        private set

    override fun retryWalletRead() = Unit
    override fun init() = Unit

    override fun createNewWallet() = Unit

    override fun continueWalletSetup() = Unit

    override fun retryWalletInitialization() = Unit

    override fun restoreWallet(
        network: ZcashNetwork,
        seedPhrase: SeedPhrase,
        birthday: BlockHeight,
        enableTor: Boolean
    ) = CompletableDeferred(Unit)

    override fun refreshFastestServers() {
        refreshCount++
    }

    override suspend fun markIronwoodAnnouncementShown() = Unit
}

private class FakeSynchronizerProvider : SynchronizerProvider {
    override val error = MutableStateFlow<SynchronizerError?>(null)
    override val synchronizer = MutableStateFlow<Synchronizer?>(MockSynchronizer(ServerValidation.Valid))
    override val isSeedMismatch = MutableStateFlow(false)

    override suspend fun getSynchronizer(): Synchronizer = checkNotNull(synchronizer.value)

    override suspend fun getSynchronizerOrNull(): Synchronizer? = synchronizer.value

    override suspend fun getVotingWalletDbPath(): String = ""

    override fun resetSynchronizer() = Unit
}

private object FakeNavigationRouter : NavigationRouter {
    override fun forward(vararg routes: Any) = Unit

    override fun replace(vararg routes: Any) = Unit

    override fun replaceAll(vararg routes: Any) = Unit

    override fun replaceFrom(route: KClass<*>, vararg routes: Any) = Unit

    override fun back() = Unit

    override fun backTo(route: KClass<*>) = Unit

    override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

    override fun backToRoot() = Unit

    override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
}
