package co.electriccoin.zcash.ui.screen.redeemgift.paste

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.usecase.ClearClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ClipboardTextIdentity
import co.electriccoin.zcash.ui.common.usecase.NavigateToRedeemGiftCardUseCase
import co.electriccoin.zcash.ui.common.usecase.ReadClipboardTextUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lets the user paste or type a gift card link and opens the redeem screen for it. The link stays in this view model's
 * memory only: it never goes into a route or the saved instance state, and it is never logged.
 */
class PasteGiftCardLinkVM(
    private val navigateToRedeemGiftCard: NavigateToRedeemGiftCardUseCase,
    private val readClipboardText: ReadClipboardTextUseCase,
    private val clearClipboard: ClearClipboardUseCase,
    private val navigationRouter: NavigationRouter,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    /** The field's text as entered. */
    private val text = MutableStateFlow("")

    private var pastedGift: ClipboardTextIdentity? = null

    private var hasContinued = false
    private var isContinuing = false

    val state: StateFlow<PasteGiftCardLinkState> =
        text.map(::createState).stateIn(viewModel = this, initialValue = createState(text.value))

    /**
     * The gift card link in [text], trimmed, or `null` while [text] is not one.
     */
    private fun linkIn(text: String): String? = text.trim().takeIf(navigateToRedeemGiftCard::isGiftCardLink)

    private fun createState(text: String): PasteGiftCardLinkState {
        val isValid = linkIn(text) != null
        return PasteGiftCardLinkState.create(
            text = text,
            isValid = isValid,
            isInvalid = !isValid && !navigateToRedeemGiftCard.isGiftCardLinkStart(text),
            onValueChange = ::onValueChange,
            onPasteClick = ::onPasteClick,
            onClearClick = ::onClearClick,
            onContinueClick = ::onContinueClick,
            onBack = ::onBack
        )
    }

    private fun onValueChange(text: String) = this.text.update { text }

    private fun onPasteClick() {
        val pasted = readClipboardText() ?: return
        pastedGift = pasted.ownership?.takeIf { linkIn(pasted.text) != null }
        text.update { pasted.text.trim() }
    }

    private fun onClearClick() = text.update { "" }

    private fun onContinueClick() {
        viewModelScope.launch {
            if (hasContinued || isContinuing || !navigationRouter.isAtEntry(sourceEntryId)) return@launch
            val link = linkIn(text.value) ?: return@launch
            isContinuing = true
            try {
                navigateToRedeemGiftCard.replaceGiftCardScanWithRedeem(link, sourceEntryId) {
                    hasContinued = true
                    clearPasted()
                    text.value = ""
                }
            } finally {
                isContinuing = false
            }
        }
    }

    private fun onBack() {
        viewModelScope.launch {
            navigationRouter.commitFrom(sourceEntryId) {
                if (!hasContinued) clearPasted()
                NavigationCommand.Back
            }
        }
    }

    private fun clearPasted() {
        val ownership = pastedGift ?: return
        pastedGift = null
        clearClipboard(ownership)
    }
}
