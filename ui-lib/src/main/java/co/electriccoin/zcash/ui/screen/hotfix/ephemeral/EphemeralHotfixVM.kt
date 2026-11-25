package co.electriccoin.zcash.ui.screen.hotfix.ephemeral

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.recoverableReads
import co.electriccoin.zcash.ui.common.usecase.FixEphemeralAddressUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EphemeralHotfixVM(
    args: EphemeralHotfixArgs,
    isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val navigationRouter: NavigationRouter,
    private val fixEphemeralAddressUseCase: FixEphemeralAddressUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val text = MutableStateFlow(args.address)

    private val isError = MutableStateFlow(false)
    private val isLoading = MutableStateFlow(false)
    private val isComplete = MutableStateFlow(false)
    private val torReadAttempts = MutableStateFlow(0L)

    init {
        addCloseable(AutoCloseable { navigationRouter.discardResultFrom(sourceEntryId) })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val torPreference = torReadAttempts.flatMapLatest {
        isTorEnabledStorageProvider.observe().recoverableReads()
    }

    val state: StateFlow<EphemeralHotfixState?> =
        combine(
            text,
            isLoading,
            isError,
            isComplete,
            torPreference,
        ) { text, isLoading, isError, isComplete, tor ->
            createState(text, isLoading, isError, isComplete, tor)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    private fun createState(
        text: String?,
        isLoading: Boolean,
        isError: Boolean,
        isComplete: Boolean,
        tor: Result<Boolean?>,
    ): EphemeralHotfixState {
        val canDiscover = tor.getOrNull() == true
        return EphemeralHotfixState(
            address =
                TextFieldState(
                    value = stringRes(text.orEmpty()),
                    onValueChange = { new ->
                        this.text.value = new
                        this.isError.value = false
                    },
                    isEnabled = !isLoading && !isComplete && canDiscover,
                    error = stringRes(R.string.ephemeral_recovery_try_again).takeIf { isError }
                ),
            button = createAction(text, isLoading, isComplete, tor),
            info = when {
                isComplete -> stringRes(R.string.ephemeral_recovery_success)
                tor.isFailure -> stringRes(R.string.ephemeral_recovery_tor_unavailable)
                canDiscover -> null
                else -> stringRes(
                    "This operation requires Tor Protection. Please enable it in the Advanced Settings."
                )
            },
            onBack = ::onBack,
            title = stringRes("Discover Funds"),
            message =
                stringRes(
                    "If you confirm, Zodl will scan the transparent address you provide and " +
                        "discover its funds. This may take a few minutes up to a few hours."
                ),
            subtitle = stringRes("Transparent Address"),
        )
    }

    private fun createAction(
        text: String?, isLoading: Boolean, isComplete: Boolean, tor: Result<Boolean?>,
    ): ButtonState =
        when {
            isComplete -> ButtonState(
                text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_close),
                onClick = ::onBack,
            )
            tor.isFailure -> ButtonState(
                text = stringRes(R.string.wallet_initialization_retry),
                onClick = ::retryTorRead,
                isEnabled = !isLoading,
                isLoading = isLoading,
            )
            else -> ButtonState(
                text = stringRes("Recover Funds"),
                onClick = ::onRecoverFundsClick,
                isEnabled = !isLoading && tor.getOrNull() == true && !text.isNullOrBlank(),
                isLoading = isLoading,
            )
        }

    private fun retryTorRead() = torReadAttempts.update { it + 1 }

    private fun onBack() = navigationRouter.back()

    private fun onRecoverFundsClick() {
        if (isComplete.value || !isLoading.compareAndSet(expect = false, update = true)) return
        isError.value = false
        val address = text.value.orEmpty()
        viewModelScope.launch {
            try {
                val discovered = fixEphemeralAddressUseCase(address, sourceEntryId)
                isComplete.value = discovered
                isError.value = !discovered
            } finally {
                isLoading.value = false
            }
        }
    }
}
