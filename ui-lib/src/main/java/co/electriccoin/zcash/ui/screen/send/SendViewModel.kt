package co.electriccoin.zcash.ui.screen.send

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.type.AddressType
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveABContactPickedUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveContactByAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.send.model.AmountField
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.RecipientAddressState
import co.electriccoin.zcash.ui.screen.send.model.SendAddressBookState
import co.electriccoin.zcash.ui.screen.send.model.SendStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class SendViewModel(
    private val sendEntryId: String,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val observeContactByAddress: ObserveContactByAddressUseCase,
    private val observeContactPicked: ObserveABContactPickedUseCase,
    private val createProposal: CreateProposalUseCase,
    private val observeWalletAccounts: GetWalletAccountsUseCase,
    private val navigateToSelectRecipient: NavigateToSelectRecipientUseCase,
    private val navigationRouter: NavigationRouter,
    private val savedStateHandle: SavedStateHandle,
    private val validateAddress: ValidateAddressUseCase,
) : ViewModel() {
    private val recipientState = MutableStateFlow(
        savedStateHandle.get<HashMap<String, String>>(RECIPIENT_STATE_KEY)
            ?.let(RecipientAddressState::fromSavedState) ?: RecipientAddressState.new("")
    )
    val recipientAddressState = recipientState.asStateFlow()

    private var onCreateZecSendClickJob: Job? = null
    private var pendingReview: Any? = null
    private var recipientValidationJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val sendAddressBookState =
        recipientAddressState
            .flatMapLatest { recipientAddressState ->
                combine(
                    observeWalletAccounts.require(),
                    observeContactByAddress(recipientAddressState.address)
                ) { accounts, contact -> accounts to contact }
                    .flatMapLatest { (accounts, contact) ->
                        flow {
                            val exists =
                                contact != null ||
                                    accounts.any { it.unifiedAddress == recipientAddressState.address }
                            val isValid = recipientAddressState.type?.isNotValid == false
                            val mode =
                                if (isValid) {
                                    if (exists) {
                                        SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK
                                    } else {
                                        SendAddressBookState.Mode.ADD_TO_ADDRESS_BOOK
                                    }
                                } else {
                                    SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK
                                }
                            val isHintVisible = !exists && isValid

                            emit(
                                SendAddressBookState(
                                    mode = mode,
                                    isHintVisible = isHintVisible,
                                    onButtonClick = { onAddressBookButtonClicked(mode, recipientAddressState) }
                                )
                            )

                            if (isHintVisible) {
                                delay(3.seconds)
                                emit(
                                    SendAddressBookState(
                                        mode = mode,
                                        isHintVisible = false,
                                        onButtonClick = { onAddressBookButtonClicked(mode, recipientAddressState) }
                                    )
                                )
                            }
                        }
                    }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                SendAddressBookState(
                    mode = SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK,
                    isHintVisible = false,
                    onButtonClick = {
                        onAddressBookButtonClicked(
                            mode = SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK,
                            recipient = recipientAddressState.value
                        )
                    }
                )
            )

    init {
        val draft = recipientState.value
        if (draft.type == null && draft.address.isNotEmpty()) validateRecipient(draft.address)
        viewModelScope.launch {
            observeContactPicked().collect {
                onRecipientAddressChanged(it)
            }
        }
        exchangeRateRepository.refreshExchangeRateUsd()
    }

    private fun onAddressBookButtonClicked(mode: SendAddressBookState.Mode, recipient: RecipientAddressState) {
        cancelProposalPreparation()
        when (mode) {
            SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK -> {
                viewModelScope.launch { navigateToSelectRecipient() }
            }

            SendAddressBookState.Mode.ADD_TO_ADDRESS_BOOK -> {
                navigationRouter.forward(AddZashiABContactArgs(recipient.address))
            }
        }
    }

    internal fun initializeRecipient(route: Send) {
        val address = route.recipientAddress
        if (address == null || savedStateHandle.contains(RECIPIENT_STATE_KEY)) return
        val type = when (route.recipientAddressType) {
            cash.z.ecc.sdk.model.AddressType.UNIFIED -> AddressType.Unified
            cash.z.ecc.sdk.model.AddressType.TRANSPARENT -> AddressType.Transparent
            cash.z.ecc.sdk.model.AddressType.SAPLING -> AddressType.Shielded
            cash.z.ecc.sdk.model.AddressType.TEX -> AddressType.Tex
            null -> return
        }
        onRecipientAddressChanged(RecipientAddressState.new(address, type))
    }

    fun onRecipientAddressChanged(state: RecipientAddressState) {
        cancelProposalPreparation()
        recipientValidationJob?.cancel()
        saveRecipient(state)
    }

    internal fun validateRecipient(address: String) {
        onRecipientAddressChanged(RecipientAddressState.new(address))
        val draft = recipientState.value
        if (address.isEmpty()) return
        recipientValidationJob = viewModelScope.launch {
            val type = validateAddress(address)
            ensureActive()
            if (recipientState.value === draft) saveRecipient(draft.copy(type = type))
        }
    }

    private fun saveRecipient(state: RecipientAddressState) {
        savedStateHandle[RECIPIENT_STATE_KEY] = state.toSavedState()
        recipientState.value = state
    }

    internal fun cancelProposalPreparation() {
        pendingReview = null
        onCreateZecSendClickJob?.cancel()
    }

    @Suppress("TooGenericExceptionCaught")
    fun onCreateZecSendClick(
        newZecSend: ZecSend,
        amountState: AmountState,
        renderedRecipient: RecipientAddressState,
        setSendStage: (SendStage) -> Unit
    ) {
        val admittedRecipient = recipientState.value
        val matchesRecipient = renderedRecipient.type is AddressType.Valid &&
            newZecSend.destination.address == renderedRecipient.address
        val matchesForm = admittedRecipient == renderedRecipient && matchesRecipient
        if (!matchesForm || onCreateZecSendClickJob?.isActive == true) return
        val attempt = Any()
        pendingReview = attempt
        val canReview = { pendingReview === attempt && recipientState.value === admittedRecipient }
        onCreateZecSendClickJob =
            viewModelScope.launch {
                try {
                    if (!canReview()) return@launch
                    val enteredInFiat = amountState.lastFieldChangedByUser == AmountField.FIAT
                    if (enteredInFiat) {
                        val current = (amountState as? AmountState.Valid)
                            ?.normalizedFiatAt(exchangeRateRepository.state.value)
                        if (current != newZecSend.amount) return@launch
                    }
                    createProposal(newZecSend, enteredInFiat, sendEntryId, canReview)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (canReview()) setSendStage(SendStage.SendFailure(e.cause?.message ?: e.message ?: ""))
                    Twig.error(e) { "Error creating proposal" }
                }
            }
    }

    private companion object {
        const val RECIPIENT_STATE_KEY = "sendRecipient"
    }
}
