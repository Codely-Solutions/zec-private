package co.electriccoin.zcash.ui.screen.restore.seed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.ClearClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ReadClipboardTextUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.SeedTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordTextFieldState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.restore.height.RestoreHeight
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class RestoreSeedVM(
    private val navigationRouter: NavigationRouter,
    private val validateSeed: ValidateSeedUseCase,
    private val readClipboardText: ReadClipboardTextUseCase,
    private val clearClipboard: ClearClipboardUseCase
) : ViewModel() {
    private val pasteError = MutableStateFlow(false)

    private val suggestions =
        flow {
            val result = withContext(Dispatchers.IO) { Mnemonics.getCachedWords(Locale.ENGLISH.language) }
            emit(result)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    @Suppress("MagicNumber")
    private val seedWords =
        MutableStateFlow(
            (0..23).map { index ->
                SeedWordTextFieldState(
                    innerState =
                        SeedWordInnerTextFieldState(
                            ""
                        ),
                    onValueChange = { onValueChange(index, it) },
                    isError = false
                )
            }
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val seedValidations =
        combine(seedWords, suggestions) { seedWords, suggestions ->
            seedWords to suggestions.orEmpty()
        }.mapLatest { (seedWords, suggestions) ->
            withContext(Dispatchers.Default) {
                seedWords.map { field ->
                    val trimmed =
                        field.innerState.value
                            .lowercase()
                            .trim()
                    val autocomplete = suggestions.filter { it.startsWith(trimmed) }
                    val validSuggestions =
                        when {
                            trimmed.isBlank() -> suggestions
                            suggestions.contains(trimmed) && autocomplete.size == 1 -> suggestions
                            else -> autocomplete
                        }
                    validSuggestions.isNotEmpty()
                }
            }
        }

    private val validSeed =
        seedWords
            .map { fields ->
                validateSeed(fields.map { it.innerState.value.trim() })
            }

    val state: StateFlow<RestoreSeedState?> =
        combine(seedWords, seedValidations, validSeed, pasteError) { words, seedValidations, validation, pasteError ->
            createState(words, seedValidations, validation.takeUnless { pasteError }).copy(isPasteError = pasteError)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    /**
     * The complete word list that the user can choose from; useful for autocomplete
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val suggestionsState =
        combine(validSeed, suggestions) { seed, suggestions ->
            seed to suggestions
        }.mapLatest { (seed, suggestions) ->
            RestoreSeedSuggestionsState(
                isVisible = seed == null && suggestions != null,
                suggestions = suggestions.orEmpty()
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    private fun createState(
        words: List<SeedWordTextFieldState>,
        seedValidations: List<Boolean>,
        seedPhrase: SeedPhrase?
    ) = RestoreSeedState(
        seed =
            SeedTextFieldState(
                values =
                    words
                        .mapIndexed { index, word ->
                            word.copy(isError = !seedValidations[index])
                        }
            ),
        onBack = ::onBack,
        pasteButton =
            ButtonState(
                text = stringRes(R.string.restore_seed_paste),
                style = ButtonStyle.SECONDARY,
                onClick = ::onPasteClicked
            ),
        dialogButton =
            IconButtonState(
                icon = R.drawable.ic_help,
                onClick = ::onInfoButtonClick
            ),
        nextButton =
            ButtonState(
                text = stringRes(R.string.general_next),
                onClick = ::onNextClicked,
            ).takeIf { seedPhrase != null }
    )

    private fun onBack() {
        navigationRouter.back()
    }

    private fun onInfoButtonClick() {
        navigationRouter.forward(SeedInfo)
    }

    private fun onNextClicked() {
        viewModelScope.launch {
            val seed = validSeed.first() ?: return@launch
            navigationRouter.forward(RestoreHeight(seed.joinToString()))
        }
    }

    private fun onValueChange(
        index: Int,
        state: SeedWordInnerTextFieldState
    ) {
        pasteError.value = false
        if (state.value.trim().any { it.isWhitespace() }) {
            pasteSeed(state.value)
        } else {
            updateSeedWord(index, state)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun onPasteClicked() {
        val text =
            try {
                readClipboardText()
            } catch (_: RuntimeException) {
                null
            }
        if (text == null || !pasteSeed(text)) {
            pasteError.value = true
            return
        }
        // Clear only the clip that supplied this phrase; a newer clip belongs to the user.
        try {
            if (readClipboardText() == text) clearClipboard()
        } catch (_: RuntimeException) {
            // Android can deny a second clipboard read. Keep the accepted phrase and newer clipboard content.
        }
    }

    private fun pasteSeed(text: String): Boolean {
        val words = text.trim().split(Regex("[\\s\\p{Z}]+")).map { it.lowercase(Locale.ROOT) }
        val seed = words.takeIf { it.size == SEED_WORD_COUNT }?.let(validateSeed::invoke)
        pasteError.value = seed == null
        if (seed != null) prefillSeed(seed)
        return seed != null
    }

    private fun updateSeedWord(
        index: Int,
        newState: SeedWordInnerTextFieldState
    ) {
        seedWords.update {
            val newSeedWords = it.toMutableList()
            newSeedWords[index] = newSeedWords[index].copy(innerState = newState.copy(value = newState.value.trim()))
            newSeedWords.toList()
        }
    }

    private fun prefillSeed(seed: SeedPhrase) {
        seedWords.update {
            val newSeedWords = it.toMutableList()
            seed.split.forEachIndexed { index, word ->
                val oldState = newSeedWords[index]
                newSeedWords[index] = oldState.copy(innerState = oldState.innerState.copy(value = word))
            }
            newSeedWords.toList()
        }
    }

    private companion object {
        const val SEED_WORD_COUNT = 24
    }
}
