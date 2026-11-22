package co.electriccoin.zcash.ui.screen.send

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.usecase.GetABContactsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveABContactPickedUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveAccountIconUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveContactByAddressUseCase
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.screen.addressbook.ADDRESS_MAX_LENGTH
import co.electriccoin.zcash.ui.screen.addressbook.AddressBookItem
import co.electriccoin.zcash.ui.screen.addressbook.SelectABRecipientArgs
import co.electriccoin.zcash.ui.screen.addressbook.SelectABRecipientVM
import co.electriccoin.zcash.ui.screen.send.model.RecipientAddressState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SendRecipientSelectionTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun contactAndUpdatedWalletAccountRowsPublishTheirValidatedAddressesToSend() = runTest {
        val contact = EnhancedABContact(
            AddressBookContact("Saved recipient", "synthetic-contact", Instant.fromEpochSeconds(0), chain = null),
            blockchain = null,
        )
        val ownAddress = "synthetic-selected-account"
        val oldAddress = "synthetic-old-wallet-recipient"
        val newAddress = "synthetic-new-wallet-recipient-".repeat(8)
        val accounts = MutableStateFlow<List<WalletAccount>?>(
            listOf(account("Sending wallet", ownAddress, selected = true), account("Old wallet", oldAddress))
        )
        val accountDataSource = mockk<AccountDataSource> { every { allAccounts } returns accounts }
        val addressBook = mockk<AddressBookRepository> {
            every { contacts } returns flowOf(listOf(contact))
            every { observeContactByAddress(any()) } answers {
                val address = firstArg<String>()
                flowOf(contact.takeIf { it.address == address })
            }
        }
        val synchronizer = mockk<Synchronizer> {
            coEvery { validateAddress(contact.address) } returns AddressType.Transparent
            coEvery { validateAddress(newAddress) } returns AddressType.Unified
        }
        val synchronizerProvider = mockk<SynchronizerProvider> {
            coEvery { getSynchronizer() } returns synchronizer
        }
        val router = mockk<NavigationRouter>(relaxed = true)
        val getWalletAccounts = GetWalletAccountsUseCase(accountDataSource)
        val selection = ObserveABContactPickedUseCase(synchronizerProvider)
        val send = SendViewModel(
            exchangeRateRepository = mockk(relaxed = true),
            observeContactByAddress = ObserveContactByAddressUseCase(addressBook),
            observeContactPicked = selection,
            createProposal = mockk(),
            observeWalletAccounts = getWalletAccounts,
            navigateToSelectRecipient = NavigateToSelectRecipientUseCase(router),
            navigationRouter = router,
        )
        val picker = SelectABRecipientVM(
            getAddressBookContacts = GetABContactsUseCase(addressBook),
            getWalletAccountsUseCase = getWalletAccounts,
            observeContactPicked = selection,
            navigationRouter = router,
            observeAccountIcon = mockk<ObserveAccountIconUseCase> {
                every { observeAll() } returns flowOf(emptyMap())
                every { iconOf(any(), any<WalletAccount>()) } returns imageRes("W")
            },
        )

        try {
            send.sendAddressBookState.value.onButtonClick()
            runCurrent()
            verify(exactly = 1) { router.forward(SelectABRecipientArgs) }

            val initial = picker.state.first { !it.isLoading }.items.filterIsInstance<AddressBookItem.Contact>()
            assertEquals(listOf(stringRes("Old wallet"), stringRes(contact.name)), initial.map { it.state.name })
            initial.last().state.onClick()

            val contactRecipient = send.recipientAddressState.first { it.address == contact.address }
            assertEquals(RecipientAddressState.new(contact.address, AddressType.Transparent), contactRecipient)
            verify(exactly = 1) { router.back() }

            send.sendAddressBookState.value.onButtonClick()
            accounts.value = listOf(
                account("Old wallet", oldAddress, selected = true),
                account("New wallet", newAddress),
            )
            val updated = picker.state.first { state ->
                state.items.filterIsInstance<AddressBookItem.Contact>().any { it.state.name == stringRes("New wallet") }
            }.items.filterIsInstance<AddressBookItem.Contact>()
            assertEquals(listOf(stringRes("New wallet"), stringRes(contact.name)), updated.map { it.state.name })
            assertEquals(stringResByAddress(newAddress.take(ADDRESS_MAX_LENGTH) + "..."), updated.first().state.address)
            updated.first().state.onClick()

            val walletRecipient = send.recipientAddressState.first { it.address == newAddress }
            assertEquals(RecipientAddressState.new(newAddress, AddressType.Unified), walletRecipient)
            verify(exactly = 2) { router.forward(SelectABRecipientArgs) }
            verify(exactly = 2) { router.back() }
            coVerify(exactly = 1) { synchronizer.validateAddress(contact.address) }
            coVerify(exactly = 1) { synchronizer.validateAddress(newAddress) }
            coVerify(exactly = 0) { synchronizer.validateAddress(oldAddress) }
        } finally {
            picker.viewModelScope.cancel()
            send.viewModelScope.cancel()
        }
    }

    private fun account(label: String, address: String, selected: Boolean = false): WalletAccount = mockk {
        every { name } returns stringRes(label)
        every { unifiedAddress } returns address
        every { isSelected } returns selected
    }
}
