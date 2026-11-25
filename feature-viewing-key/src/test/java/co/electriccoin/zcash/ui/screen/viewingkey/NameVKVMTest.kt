package co.electriccoin.zcash.ui.screen.viewingkey

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.preference.AndroidPreferenceProvider
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AccountNameLimit
import co.electriccoin.zcash.ui.common.model.LceError
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccountImportException
import co.electriccoin.zcash.ui.common.model.ViewOnlyPendingImportMissingException
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SelectedAccountUUIDProviderImpl
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.PendingViewOnlyImport
import co.electriccoin.zcash.ui.common.repository.ViewOnlyPendingImportRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.CreateHWWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.ImportViewOnlyAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToColorPickerUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewOnlyAccountFixtures
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.colorpicker.ColorPickerArgs
import co.electriccoin.zcash.ui.screen.connecthw.HWWalletEnrollment
import co.electriccoin.zcash.ui.screen.connecthw.KeystoneEnrollmentSession
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenArgs
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenFlow
import co.electriccoin.zcash.ui.screen.viewingkey.alreadyimported.AlreadyImportedVKArgs
import co.electriccoin.zcash.ui.screen.viewingkey.name.NameVKState
import co.electriccoin.zcash.ui.screen.viewingkey.name.NameVKVM
import co.electriccoin.zcash.viewingkey.ViewOnlyAccountImporterImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class NameVKVMTest : NameVKVMFixture() {
    @Test
    fun aLongerNameIsCutToTwelveCharacters() =
        runTest {
            val vm = vm()
            collect(vm)

            type(vm, "Zodl Savings 2026")

            assertEquals(
                "Zodl Savings",
                vm
                    .content()
                    .nameField.field.value
                    .text()
            )
            assertEquals(
                listOf(12, 12),
                assertNotNull(vm.content().nameField.counter)
                    .args()
            )
        }

    @Test
    fun emojiCountOnceAndAreNeverCutInHalf() =
        runTest {
            val vm = vm()
            collect(vm)

            type(vm, "Vault " + LOCK.repeat(8))

            assertEquals(
                "Vault " + LOCK.repeat(6),
                vm
                    .content()
                    .nameField.field.value
                    .text()
            )
            assertEquals(
                listOf(12, 12),
                assertNotNull(vm.content().nameField.counter)
                    .args()
            )

            type(vm, LOCK.repeat(3))

            assertEquals(
                listOf(3, 12),
                assertNotNull(vm.content().nameField.counter)
                    .args()
            )
        }

    @Test
    fun theCounterFollowsTheName() =
        runTest {
            val vm = vm()
            collect(vm)
            assertEquals(
                listOf(0, AccountNameLimit.MAX_LENGTH),
                assertNotNull(vm.content().nameField.counter)
                    .args()
            )

            type(vm, "Zodl Vault")

            assertEquals(
                listOf(10, AccountNameLimit.MAX_LENGTH),
                assertNotNull(vm.content().nameField.counter)
                    .args()
            )
        }

    @Test
    fun continueNeedsANameThatIsNotBlank() =
        runTest {
            val vm = vm()
            collect(vm)
            assertFalse(vm.content().continueButton.isEnabled)

            type(vm, "   ")
            assertFalse(vm.content().continueButton.isEnabled)
            vm
                .content()
                .continueButton
                .onClick()
            runCurrent()
            coVerify(exactly = 0) { importViewOnlyAccount.invoke() }

            type(vm, "Vault")
            assertTrue(vm.content().continueButton.isEnabled)
        }

    @Test
    fun aNameAnotherAccountShowsDisablesContinueAndShowsTheError() =
        runTest {
            val vm = vm()
            collect(vm)

            type(vm, " keystone ")

            assertFalse(vm.content().continueButton.isEnabled)
            assertEquals(
                R.string.accountName_duplicate,
                (
                    vm
                        .content()
                        .nameField.field.error as StringResource.ByResource
                ).resource
            )
            vm
                .content()
                .continueButton
                .onClick()
            runCurrent()
            coVerify(exactly = 0) { importViewOnlyAccount.invoke() }
            assertEquals(pending(), repository.get())
        }

    @Test
    fun aUniqueNameEnablesContinueWithoutAnError() =
        runTest {
            val vm = vm()
            collect(vm)

            type(vm, "ZODL")
            assertFalse(vm.content().continueButton.isEnabled)

            type(vm, "Zodl 2")

            assertTrue(vm.content().continueButton.isEnabled)
            assertNull(
                vm
                    .content()
                    .nameField.field.error
            )
        }

    @Test
    fun theDuplicateErrorAlwaysBelongsToTheNameShown() =
        runTest {
            val vm = vm()
            val states = mutableListOf<NameVKState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.state.collect { state -> state.content?.let { states.add(it) } }
            }
            runCurrent()

            listOf("Keystone", "Savings", "zodl", "Vault").forEach { type(vm, it) }

            states.forEach { state ->
                val shown =
                    state.nameField.field.value
                        .text()
                val isDuplicate = shown.trim().lowercase() in setOf("keystone", "zodl")
                assertEquals(isDuplicate, state.nameField.field.error != null, shown)
            }
        }

    @Test
    fun continueRechecksTheNameAgainstTheAccountsWhenTapped() =
        runTest {
            val vm = vm()
            collect(vm)
            type(vm, "Savings")
            val continueButton = vm.content().continueButton
            assertTrue(continueButton.isEnabled)

            accounts.value = listOf(ViewOnlyAccountFixtures.viewOnly(customName = "Savings"))
            continueButton.onClick()
            runCurrent()

            coVerify(exactly = 0) { importViewOnlyAccount.invoke() }
            assertEquals(pending(), repository.get())
        }

    @Test
    fun doneOnTheKeyboardDoesNothingForABlankOrDuplicateName() =
        runTest {
            val vm = vm()
            collect(vm)

            type(vm, "   ")
            vm
                .content()
                .nameField
                .onDone()
            runCurrent()
            type(vm, "Keystone")
            vm
                .content()
                .nameField
                .onDone()
            runCurrent()

            coVerify(exactly = 0) { importViewOnlyAccount.invoke() }
            assertEquals(pending(), repository.get())
        }

    @Test
    fun doneOnTheKeyboardContinuesWhileContinueIsEnabled() =
        runTest {
            val vm = vm()
            collect(vm)
            type(vm, "Vault")

            vm
                .content()
                .nameField
                .onDone()
            runCurrent()

            assertEquals(pending(name = "Vault"), repository.get())
            coVerify(exactly = 1) { importViewOnlyAccount.invoke() }
        }

    @Test
    fun withoutAPendingKeyTheFlowReturnsToTheRoot() =
        runTest {
            repository.clear()

            vm()

            verify(exactly = 1) { navigationRouter.backToRoot() }
        }

    @Test
    fun aPickedColorShowsOnTheAvatarAndContinueKeepsItWithThePendingImport() =
        runTest {
            val vm = vm()
            collect(vm)
            assertNull(vm.content().avatarColor())

            openPicker(vm)
            val request = pickerRequests.single()
            assertNull(request.initialArgb)
            navigateToColorPicker.onPicked(Color(PICKED), request)
            navigateToColorPicker.onClosed(request)
            runCurrent()

            assertEquals(Color(PICKED), vm.content().avatarColor())
            assertEquals(pending(), repository.get())

            type(vm, "Savings")
            vm
                .content()
                .continueButton
                .onClick()
            runCurrent()

            assertEquals(pending(name = "Savings", color = Color(PICKED)), repository.get())
        }

    @Test
    fun theColorOfThePendingImportShowsWhenTheScreenOpensAgain() =
        runTest {
            repository.set(pending(name = "Savings", color = Color(PICKED)))
            val vm = vm()
            collect(vm)

            assertEquals(Color(PICKED), vm.content().avatarColor())
        }

    @Test
    fun aPickerClosedWithoutAPickKeepsTheColorAndReopensWithIt() =
        runTest {
            val vm = vm()
            collect(vm)
            openPicker(vm)
            navigateToColorPicker.onPicked(Color(PICKED), pickerRequests.last())
            navigateToColorPicker.onClosed(pickerRequests.last())
            runCurrent()

            openPicker(vm)
            val second = pickerRequests.last()
            assertEquals(Color(PICKED), Color(assertNotNull(second.initialArgb)))
            navigateToColorPicker.onClosed(second)
            runCurrent()

            assertEquals(Color(PICKED), vm.content().avatarColor())
        }

    @Test
    fun theAvatarFollowsLivePicksAndKeepsTheLastOneWhenThePickerCloses() =
        runTest {
            val vm = vm()
            collect(vm)
            openPicker(vm)
            val request = pickerRequests.single()

            navigateToColorPicker.onPicked(Color(OTHER), request)
            runCurrent()
            assertEquals(Color(OTHER), vm.content().avatarColor())

            navigateToColorPicker.onPicked(Color(PICKED), request)
            navigateToColorPicker.onClosed(request)
            runCurrent()
            assertEquals(Color(PICKED), vm.content().avatarColor())

            openPicker(vm)
            assertEquals(2, pickerRequests.size)
        }

    @Test
    fun aSecondTapWhileThePickerIsOpenOpensNothing() =
        runTest {
            val vm = vm()
            collect(vm)

            openPicker(vm)
            openPicker(vm)

            assertEquals(1, pickerRequests.size)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class NameVKImportVMTest : NameVKVMFixture() {
    @Test
    fun continueImportsWithTheTrimmedNameTheColorAndTheBirthdayAndShowsItsProgress() =
        runTest {
            val importing = CompletableDeferred<Unit>()
            var imported: PendingViewOnlyImport? = null
            importAnswer = {
                imported = repository.get()
                importing.await()
            }
            val vm = vm()
            collect(vm)
            pickColor(vm, Color(PICKED))
            type(vm, " Vault ")

            vm
                .content()
                .continueButton
                .onClick()
            runCurrent()

            assertEquals(pending(name = "Vault", color = Color(PICKED)), imported)
            coVerify(exactly = 1) { importViewOnlyAccount.invoke() }
            assertTrue(vm.content().continueButton.isLoading)
            assertFalse(vm.content().continueButton.isEnabled)

            importing.complete(Unit)
            runCurrent()

            assertFalse(vm.content().continueButton.isLoading)
            verify(exactly = 0) { navigationRouter.backToRoot() }
        }

    @Test
    fun aDoubleTapImportsOnce() =
        runTest {
            val importing = CompletableDeferred<Unit>()
            importAnswer = { importing.await() }
            val vm = vm()
            collect(vm)
            type(vm, "Vault")
            val continueButton = vm.content().continueButton

            continueButton.onClick()
            continueButton.onClick()
            runCurrent()
            continueButton.onClick()
            vm
                .content()
                .continueButton
                .onClick()
            runCurrent()

            coVerify(exactly = 1) { importViewOnlyAccount.invoke() }
            importing.complete(Unit)
        }

    @Test
    fun withoutAPendingKeyAtContinueTheFlowReturnsToTheRoot() =
        runTest {
            val vm = vm()
            collect(vm)
            type(vm, "Vault")
            repository.clear()

            vm
                .content()
                .continueButton
                .onClick()
            runCurrent()

            verify(exactly = 1) { navigationRouter.backToRoot() }
            coVerify(exactly = 0) { importViewOnlyAccount.invoke() }
        }

    @Test
    fun withoutABirthdayAtContinueTheFlowReturnsToTheRoot() =
        runTest {
            repository.set(PendingViewOnlyImport(ufvk = UFVK, name = ""))
            val vm = vm()
            collect(vm)
            type(vm, "Vault")

            vm
                .content()
                .continueButton
                .onClick()
            runCurrent()

            verify(exactly = 1) { navigationRouter.backToRoot() }
            coVerify(exactly = 0) { importViewOnlyAccount.invoke() }
            assertEquals(PendingViewOnlyImport(ufvk = UFVK, name = ""), repository.get())
        }

    @Test
    fun aPendingKeyGoneDuringTheImportReturnsToTheRoot() =
        runTest {
            importAnswer = { throw ViewOnlyPendingImportMissingException() }
            val vm = vm()
            collect(vm)
            type(vm, "Vault")

            vm
                .content()
                .continueButton
                .onClick()
            runCurrent()

            verify(exactly = 1) { navigationRouter.backToRoot() }
            assertNull(vm.state.value.error)
        }

    @Test
    fun aFailedImportShowsTheErrorAndRetryImportsAgain() =
        runTest {
            var calls = 0
            importAnswer = {
                calls++
                if (calls == 1) throw ViewOnlyAccountImportException()
            }
            val vm = vm()
            collect(vm)
            type(vm, "Vault")

            vm
                .content()
                .continueButton
                .onClick()
            runCurrent()

            val error = assertIs<LceError.BottomSheet>(vm.state.value.error)
            assertFalse(vm.content().continueButton.isLoading)
            assertTrue(vm.content().continueButton.isEnabled)

            error.state.primaryAction
                .onClick()
            runCurrent()

            coVerify(exactly = 2) { importViewOnlyAccount.invoke() }
            assertNull(vm.state.value.error)
            assertEquals(pending(name = "Vault"), repository.get())
        }

    @Test
    fun backFromNameCanFinishTheCommittedImportAfterSelectionCommitFailed() = runTest {
        val previous = ViewOnlyAccountFixtures.zodlUuid
        val previousEncoding = Base64.getEncoder().encodeToString(previous.value)
        val storage = SelectionStorage(previousEncoding)
        val preferences = AndroidPreferenceProvider(storage.preferences, StandardTestDispatcher(testScheduler))
        val selected = SelectedAccountUUIDProviderImpl(mockk<EncryptedPreferenceProvider> {
            coEvery { this@mockk.invoke() } returns preferences
        })
        val visible = ViewOnlyAccountFixtures.viewOnly(customName = "Vault")
        val created = visible.sdkAccount
        val accountDataSource = mockk<AccountDataSource> {
            every { allAccounts } returns accounts
            coEvery { importViewOnlyAccount(UFVK, "Vault", BIRTHDAY) } coAnswers {
                accounts.value = listOf(ViewOnlyAccountFixtures.zodl(), visible)
                created
            }
            coEvery { selectAccount(created) } coAnswers { selected.setUUID(created.accountUuid) }
        }
        val synchronizer = mockk<SynchronizerProvider>(relaxed = true)
        val importer = ImportViewOnlyAccountUseCase(
            repository, accountDataSource, synchronizer, navigationRouter, mockk(relaxed = true)
        )
        val vm = vm(importer)
        collect(vm)
        type(vm, "Vault")

        vm.content().continueButton.onClick()
        runCurrent()

        assertIs<LceError.BottomSheet>(vm.state.value.error)
        assertEquals(previous, selected.getUUID())
        assertEquals(previousEncoding, storage.durable)
        assertEquals(created, repository.get()?.importedAccount)
        assertTrue(vm.content().continueButton.isEnabled)
        assertFalse(vm.content().nameField.field.isEnabled)
        verify(exactly = 0) { synchronizer.resetSynchronizer() }
        verify(exactly = 0) { navigationRouter.forward(*anyVararg()) }

        val committed = checkNotNull(repository.get()?.committedBirthday)
        assertEquals(committed.message(), vm.content().form.header.message)
        val oldFinish = vm.content().continueButton
        vm.content().onBack()
        oldFinish.onClick()
        runCurrent()
        verify(exactly = 1) { navigationRouter.back() }
        coVerify(exactly = 1) { accountDataSource.selectAccount(created) }

        val shared = CreateHWWalletAccountUseCase(
            keystoneSession = KeystoneEnrollmentSession(),
            createKeystoneAccount = mockk(),
            ledgerAccountImporter = mockk(),
            viewOnlyAccountImporter = ViewOnlyAccountImporterImpl(repository, navigationRouter, importer),
            navigationRouter = navigationRouter,
        )
        assertEquals(committed, shared.committedBirthday(HWWalletEnrollment.ViewOnly))
        shared.finish(HWWalletEnrollment.ViewOnly, committed)
        runCurrent()

        assertEquals(created.accountUuid, selected.getUUID())
        assertEquals(Base64.getEncoder().encodeToString(created.accountUuid.value), storage.durable)
        assertNull(repository.get())
        coVerify(exactly = 1) { accountDataSource.importViewOnlyAccount(any(), any(), any()) }
        coVerify(exactly = 2) { accountDataSource.selectAccount(created) }
        verify(exactly = 1) { synchronizer.resetSynchronizer() }
        verify(exactly = 0) { navigationRouter.forward(AlreadyImportedVKArgs) }
        coVerifyOrder {
            accountDataSource.selectAccount(created)
            synchronizer.resetSynchronizer()
            navigationRouter.forward(KeepOpenArgs(KeepOpenFlow.VIEW_ONLY))
        }
    }

    @Test
    fun backWhileImportingAndEditsWhileImportingAreIgnored() =
        runTest {
            val importing = CompletableDeferred<Unit>()
            importAnswer = { importing.await() }
            val vm = vm()
            collect(vm)
            type(vm, "Vault")
            vm
                .content()
                .continueButton
                .onClick()
            runCurrent()

            vm.content().onBack()
            type(vm, "Savings")
            openPicker(vm)

            verify(exactly = 0) { navigationRouter.back() }
            assertEquals(
                "Vault",
                vm
                    .content()
                    .nameField.field.value
                    .text()
            )
            assertTrue(pickerRequests.isEmpty())
            importing.complete(Unit)
        }

    @Test
    fun theNameFieldIsDisabledWhileImportingAndEnabledAgainAfterAFailedImport() =
        runTest {
            val importing = CompletableDeferred<Unit>()
            importAnswer = {
                importing.await()
                throw ViewOnlyAccountImportException()
            }
            val vm = vm()
            collect(vm)
            type(vm, "Vault")
            assertTrue(
                vm
                    .content()
                    .nameField.field.isEnabled
            )

            vm
                .content()
                .continueButton
                .onClick()
            runCurrent()

            assertFalse(
                vm
                    .content()
                    .nameField.field.isEnabled
            )
            assertEquals(
                listOf(5, AccountNameLimit.MAX_LENGTH),
                assertNotNull(vm.content().nameField.counter)
                    .args()
            )

            importing.complete(Unit)
            runCurrent()

            assertIs<LceError.BottomSheet>(vm.state.value.error)
            assertTrue(
                vm
                    .content()
                    .nameField.field.isEnabled
            )
        }

    @Test
    fun refusedBackKeepsNameEditableAndAllowsTheOwnedBackToRetry() = runTest {
        val nameEntry = mockk<NavBackStackEntry> { every { id } returns "name" }
        var top = mockk<NavBackStackEntry> { every { id } returns "scanner" }
        var committedBacks = 0
        every { navigationRouter.custom(any()) } answers {
            val command = firstArg<(NavBackStackEntry?) -> NavigationCommand?>()(top)
            if (command != null) {
                val owned = assertIs<NavigationCommand.ForEntry>(command)
                assertTrue(owned.isStillAllowed())
                assertEquals(NavigationCommand.Back, owned.buildCommand())
                owned.acknowledge(true)
                committedBacks++
            }
        }
        val vm = vm(sourceEntryId = "name")
        collect(vm)

        vm.content().onBack()
        runCurrent()
        assertEquals(0, committedBacks)
        type(vm, "Vault")
        assertEquals("Vault", vm.content().nameField.field.value.text())

        top = nameEntry
        vm.content().onBack()
        runCurrent()
        type(vm, "Ignored")
        assertEquals(1, committedBacks)
        assertEquals("Vault", vm.content().nameField.field.value.text())
        verify(exactly = 0) { navigationRouter.back() }
    }

    @Test
    fun backReturnsToTheBirthdayAndKeepsThePendingKeyAndBirthday() =
        runTest {
            val vm = vm()
            collect(vm)
            type(vm, "Vault")

            vm.content().onBack()

            assertEquals(pending(), repository.get())
            verify(exactly = 1) { navigationRouter.back() }
            verify(exactly = 0) { navigationRouter.backToRoot() }
            coVerify(exactly = 0) { importViewOnlyAccount.invoke() }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class NameVKVMFixture {
    protected val navigationRouter = mockk<NavigationRouter>(relaxed = true)

    protected val pickerRequests = mutableListOf<ColorPickerArgs>()

    protected val navigateToColorPicker =
        NavigateToColorPickerUseCase(
            mockk(relaxed = true) {
                every { forward(*anyVararg()) } answers {
                    (args[0] as Array<*>).filterIsInstance<ColorPickerArgs>().forEach { pickerRequests.add(it) }
                }
            }
        )

    protected val accounts =
        MutableStateFlow<List<WalletAccount>?>(
            listOf(ViewOnlyAccountFixtures.zodl(), ViewOnlyAccountFixtures.viewOnly(customName = "Keystone"))
        )

    private val isAccountNameTaken = ViewOnlyAccountFixtures.isAccountNameTaken(accounts)

    protected val repository = ViewOnlyPendingImportRepositoryImpl().apply { set(pending()) }

    protected var importAnswer: suspend () -> Unit = {}

    protected val importViewOnlyAccount =
        mockk<ImportViewOnlyAccountUseCase> {
            coEvery { this@mockk.invoke() } coAnswers { importAnswer() }
        }

    private val errorStateMapper =
        ErrorMapperUseCase(
            reportAccess = mockk(relaxed = true),
            sendEmail = mockk(relaxed = true),
            viewOnlyContent = mockk(relaxed = true),
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    protected fun NameVKState.avatarColor() =
        (form.header.avatar.image as ImageResource.Layered).background.takeIf { it.isSpecified }

    protected fun TestScope.openPicker(vm: NameVKVM) {
        assertNotNull(
            vm
                .content()
                .form.header.avatar.editButton
        ).onClick()
        runCurrent()
    }

    protected fun TestScope.pickColor(
        vm: NameVKVM,
        color: Color,
    ) {
        openPicker(vm)
        val request = pickerRequests.last()
        navigateToColorPicker.onPicked(color, request)
        navigateToColorPicker.onClosed(request)
        runCurrent()
    }

    protected fun NameVKVM.content() = assertNotNull(state.value.content)

    protected fun pending(
        name: String = "",
        color: Color? = null,
    ) = PendingViewOnlyImport(ufvk = UFVK, name = name, color = color, birthday = BIRTHDAY)

    protected fun TestScope.type(
        vm: NameVKVM,
        text: String,
    ) {
        vm
            .content()
            .nameField.field
            .onValueChange(text)
        runCurrent()
    }

    protected fun vm(
        importer: ImportViewOnlyAccountUseCase = importViewOnlyAccount,
        sourceEntryId: String? = null,
    ) =
        NameVKVM(
            pendingImportRepository = repository,
            navigationRouter = navigationRouter,
            navigateToColorPicker = navigateToColorPicker,
            isAccountNameTaken = isAccountNameTaken,
            importViewOnlyAccount = importer,
            errorStateMapper = errorStateMapper,
            sourceEntryId = sourceEntryId,
        )

    protected fun TestScope.collect(vm: NameVKVM) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        runCurrent()
    }

    protected fun StringResource.text() = (this as StringResource.ByString).value

    protected fun StringResource.args() = (this as StringResource.ByResource).args.toList()
}

private const val UFVK = "uview1namefixture"
private const val PICKED = 0x80BE38F3
private const val OTHER = 0xFF0088FF
private val BIRTHDAY = BlockHeight.new(2_500_000L)

private class SelectionStorage(initial: String) {
    var durable: String? = initial
        private set
    private var cached: String? = initial
    private var staged: String? = initial
    private var failed = false

    val preferences = mockk<SharedPreferences> {
        every { getString("selected_account_uuid", null) } answers { cached }
    }

    init {
        val editor = mockk<SharedPreferences.Editor> {
            every { putString("selected_account_uuid", any()) } answers {
                staged = secondArg()
                this@mockk
            }
            every { commit() } answers {
                cached = staged
                if (failed) {
                    durable = staged
                    true
                } else {
                    failed = true
                    false
                }
            }
        }
        every { preferences.edit() } returns editor
    }
}

private const val LOCK = "\uD83D\uDD10"
