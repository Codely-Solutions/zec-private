package co.electriccoin.zcash.ui.screen.swap.ab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.usecase.GetABSwapContactsUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectABSwapRecipientUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.listitem.ContactListItemState
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.screen.addressbook.AddressBookItem
import co.electriccoin.zcash.ui.screen.addressbook.AddressBookState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SelectSwapABRecipientVM(
    getAddressBookSwapContacts: GetABSwapContactsUseCase,
    private val args: SelectABSwapRecipientArgs,
    private val navigateToSelectSwapRecipient: NavigateToSelectABSwapRecipientUseCase,
    private val navigationRouter: NavigationRouter,
    private val navigateToScanAddress: NavigateToScanGenericAddressUseCase
) : ViewModel() {
    val state =
        getAddressBookSwapContacts
            .observe()
            .map { contacts ->
                createState(
                    contacts?.filter {
                        args.network == null || args.network.equals(it.blockchain?.chainTicker, ignoreCase = true)
                    }
                )
            }.flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createState(contacts = null)
            )

    private fun createState(contacts: List<EnhancedABContact>?): AddressBookState =
        AddressBookState(
            isLoading = contacts == null,
            items =
                contacts
                    ?.map { contact ->
                        AddressBookItem.Contact(
                            ContactListItemState(
                                bigIcon = getContactInitials(contact),
                                smallIcon = contact.blockchain?.chainIcon,
                                isShielded = false,
                                name = stringRes(contact.contact.name),
                                address = stringResByAddress(contact.contact.address),
                                onClick = { onContactClick(contact) },
                            )
                        )
                    }.orEmpty().let { items ->
                        if (args.network == null || contacts == null || items.isNotEmpty()) {
                            items
                        } else {
                            listOf(
                                AddressBookItem.Title(
                                    stringRes(
                                        R.string.private_contacts_network_empty,
                                        stringRes(args.network.uppercase())
                                    )
                                )
                            ) + items
                        }
                    },
            onBack = ::onBack,
            manualButton =
                ButtonState(
                    onClick = ::onAddContactManuallyClick,
                    text = stringRes(R.string.addressBook_manualEntry)
                ),
            scanButton =
                ButtonState(
                    onClick = ::onScanContactClick,
                    text = stringRes(R.string.addressBook_scanAddress)
                ),
            title =
                args.network?.let { stringRes(R.string.private_contacts_network_title, stringRes(it.uppercase())) }
                    ?: stringRes(R.string.addressBook_selectRecipient),
            info = null
        )

    private fun getContactInitials(contact: EnhancedABContact): ImageResource =
        imageRes(
            contact.contact.name
                .split(" ")
                .mapNotNull { part ->
                    part.takeIf { it.isNotEmpty() }?.first()?.toString()
                }.take(2)
                .joinToString(separator = "")
        )

    private fun onBack() = viewModelScope.launch { navigateToSelectSwapRecipient.onSelectionCancelled(args) }

    private fun onContactClick(contact: EnhancedABContact) =
        viewModelScope.launch { navigateToSelectSwapRecipient.onSelected(contact, args) }

    private fun onAddContactManuallyClick() =
        navigationRouter.forward(
            AddSwapABContactArgs(
                address = null,
                network = args.network
            )
        )

    private fun onScanContactClick() =
        viewModelScope.launch {
            val contact = navigateToScanAddress()
            if (contact != null) {
                navigationRouter.replace(AddSwapABContactArgs(address = contact.address, network = args.network))
            }
        }
}
