package co.electriccoin.zcash.ui.screen.transactionnote.viewmodel

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MetadataDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.MetadataRepositoryImpl
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataKey
import co.electriccoin.zcash.ui.common.usecase.CreateOrUpdateTransactionNoteUseCase
import co.electriccoin.zcash.ui.common.usecase.DeleteTransactionNoteUseCase
import co.electriccoin.zcash.ui.common.usecase.GetTransactionMetadataUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.transactionnote.TransactionNote
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Editor, mutation/read use cases, repository and reset gate are real; storage/SDK/navigation are ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotePersistenceBoundaryTest {
    private val owner = AccountUuid.new(ByteArray(16))
    private val account = mockk<WalletAccount> { every { sdkAccount } returns Account.new(owner) }
    private val other = mockk<WalletAccount> {
        every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16) { 1 }))
    }
    private val selected = MutableStateFlow<WalletAccount?>(account)
    private val key = mockk<MetadataKey>()
    private val storage = mockk<MetadataDataSource>()
    private val gate = WalletWriteAccess()
    private val router = mockk<NavigationRouter>(relaxed = true)
    private var durableNote: String? = "Stored note"
    private val acknowledgements = Channel<Boolean>(Channel.UNLIMITED)
    private var renameBeforeFailure = false
    private val repository = MetadataRepositoryImpl(
        accountDataSource = mockk<AccountDataSource> {
            every { selectedAccount } returns selected
            every { allAccounts } returns MutableStateFlow<List<WalletAccount>?>(listOf(account, other))
        },
        metadataDataSource = storage,
        metadataKeyStorageProvider = mockk { coEvery { get(owner) } returns key },
        persistableWalletProvider = mockk(),
        simpleSwapAssetProvider = mockk(relaxed = true),
        walletWriteAccess = gate,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        coEvery { storage.readTxNote("tx", key) } coAnswers { durableNote }
        coEvery { storage.createOrUpdateTxNote("tx", any(), key) } coAnswers {
            val acknowledged = acknowledgements.receive()
            if (acknowledged || renameBeforeFailure) durableNote = secondArg()
            acknowledged
        }
        coEvery { storage.deleteTxNote("tx", key) } coAnswers {
            val acknowledged = acknowledgements.receive()
            if (acknowledged || renameBeforeFailure) durableNote = null
            acknowledged
        }
    }

    @After
    fun teardown() = Dispatchers.resetMain()

    @Test
    fun failedWriteRetainsDraftAndRetryStaysWithDisplayedAccount() = runTest {
        val editor = open()
        editor.state.value.note.onValueChange("New draft")
        val ready = editor.state.first { it.note.value == stringRes("New draft") }
        selected.value = other
        assertNotNull(ready.secondaryButton).onClick()
        editor.state.first { it.isSaving }
        acknowledgements.send(false)
        val failed = editor.state.first { !it.isSaving }
        assertEquals(stringRes("New draft"), failed.note.value)
        assertEquals("Stored note", durableNote)
        verify(exactly = 0) { router.back() }
        assertNotNull(failed.secondaryButton).onClick()
        editor.state.first { it.isSaving }
        acknowledgements.send(true)
        editor.state.first { !it.isSaving }
        assertEquals("New draft", durableNote)
        coVerify(exactly = 2) { storage.createOrUpdateTxNote("tx", "New draft", key) }
        verify(exactly = 1) { router.back() }
        assertEquals(stringRes("New draft"), open().state.value.note.value)
    }

    @Test
    fun unacknowledgedDeletionCanBeRetriedThroughTheSameEditor() = runTest {
        renameBeforeFailure = true
        val editor = open()
        val delete = assertNotNull(editor.state.value.negative)
        delete.onClick()
        editor.state.first { it.isSaving }
        acknowledgements.send(false)
        val failed = editor.state.first { !it.isSaving }
        assertEquals(null, durableNote)
        assertEquals(stringRes("Stored note"), failed.note.value)
        verify(exactly = 0) { router.back() }
        assertNotNull(failed.negative).onClick()
        editor.state.first { it.isSaving }
        acknowledgements.send(true)
        editor.state.first { !it.isSaving }
        coVerify(exactly = 2) { storage.deleteTxNote("tx", key) }
        verify(exactly = 1) { router.back() }
        assertEquals(stringRes(""), open().state.value.note.value)
    }

    @Test
    fun unacknowledgedRenameRetainsDraftAndResetRejectsItsRetry() = runTest {
        renameBeforeFailure = true
        val editor = open()
        editor.state.value.note.onValueChange("Renamed draft")
        val ready = editor.state.first { it.note.value == stringRes("Renamed draft") }
        assertNotNull(ready.secondaryButton).onClick()
        editor.state.first { it.isSaving }
        acknowledgements.send(false)
        val failed = editor.state.first { !it.isSaving }
        assertEquals("Renamed draft", durableNote)
        assertEquals(stringRes("Renamed draft"), failed.note.value)
        verify(exactly = 0) { router.back() }
        gate.beginReset()
        assertNotNull(failed.secondaryButton).onClick()
        // A newer wallet cannot admit the old editor's retry, including held callbacks.
        advanceUntilIdle()
        val rejected = editor.state.first { !it.isSaving }
        assertEquals(stringRes("Renamed draft"), rejected.note.value)
        coVerify(exactly = 1) { storage.createOrUpdateTxNote(any(), any(), any()) }
        verify(exactly = 0) { router.back() }
    }

    private suspend fun TestScope.open(): TransactionNoteViewModel {
        val editor = TransactionNoteViewModel(
            TransactionNote("tx", owner.toStorageKeyId(), 0), router,
            GetTransactionMetadataUseCase(repository),
            CreateOrUpdateTransactionNoteUseCase(repository, router, mockk(relaxed = true)),
            DeleteTransactionNoteUseCase(repository, router, mockk(relaxed = true)),
        )
        backgroundScope.launch { editor.state.collect { } }
        editor.state.first { it.note.isEnabled }
        return editor
    }
}
