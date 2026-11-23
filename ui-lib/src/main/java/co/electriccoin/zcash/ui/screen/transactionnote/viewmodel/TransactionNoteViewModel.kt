package co.electriccoin.zcash.ui.screen.transactionnote.viewmodel

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.CreateOrUpdateTransactionNoteUseCase
import co.electriccoin.zcash.ui.common.usecase.DeleteTransactionNoteUseCase
import co.electriccoin.zcash.ui.common.usecase.GetTransactionMetadataUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.StringResourceColor
import co.electriccoin.zcash.ui.design.util.StyledStringStyle
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.transactionnote.TransactionNote
import co.electriccoin.zcash.ui.screen.transactionnote.model.TransactionNoteState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class TransactionNoteViewModel(
    private val transactionNote: TransactionNote,
    private val navigationRouter: NavigationRouter,
    private val getTransactionNote: GetTransactionMetadataUseCase,
    private val createOrUpdateTransactionNote: CreateOrUpdateTransactionNoteUseCase,
    private val deleteTransactionNote: DeleteTransactionNoteUseCase
) : ViewModel() {
    private val editor = MutableStateFlow<NoteEditor>(NoteEditor.Loading)
    private val runningSave = MutableStateFlow<NoteSave?>(null)
    private var loadJob: Job? = null
    private var dismissed = false

    val state: StateFlow<TransactionNoteState> =
        combine(editor, runningSave, ::createState).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(editor.value, runningSave = null)
        )

    private fun createState(
        editor: NoteEditor,
        runningSave: NoteSave?
    ): TransactionNoteState {
        val ready = editor as? NoteEditor.Ready
        val noteText = ready?.text.orEmpty()
        val isNoteTextTooLong = noteText.length > MAX_NOTE_LENGTH
        val canEdit = !dismissed && runningSave == null
        val canSave = canEdit && !isNoteTextTooLong && noteText.isNotBlank()
        return TransactionNoteState(
            onBack = ::onBack,
            title =
                if (ready?.hasSavedNote != true) {
                    stringRes(R.string.annotation_addArticle)
                } else {
                    stringRes(R.string.annotation_edit)
                },
            note =
                TextFieldState(
                    value = stringRes(noteText),
                    error = stringRes("").takeIf { isNoteTextTooLong },
                    isEnabled = ready != null && canEdit,
                    onValueChange = if (ready != null) ::onNoteTextChanged else { _ -> },
                ),
            noteCharacters =
                stringRes(R.string.transaction_note_max_length, noteText.length.toString())
                    .withStyle(
                        StyledStringStyle(
                            color = if (isNoteTextTooLong) StringResourceColor.NEGATIVE else StringResourceColor.PRIMARY
                        )
                    ),
            primaryButton =
                ButtonState(
                    text = stringRes(R.string.annotation_add),
                    onClick = ::onAddOrUpdateNoteClick,
                    isEnabled = canSave,
                    isLoading = runningSave == NoteSave.SAVE,
                    hapticFeedbackType = HapticFeedbackType.Confirm
                ).takeIf { ready?.hasSavedNote == false },
            secondaryButton =
                ButtonState(
                    text = stringRes(R.string.annotation_save),
                    onClick = ::onAddOrUpdateNoteClick,
                    isEnabled = canSave,
                    isLoading = runningSave == NoteSave.SAVE,
                    hapticFeedbackType = HapticFeedbackType.Confirm
                ).takeIf { ready?.hasSavedNote == true },
            negative =
                ButtonState(
                    text = stringRes(R.string.annotation_delete),
                    isEnabled = canEdit,
                    onClick = ::onDeleteNoteClick,
                    isLoading = runningSave == NoteSave.DELETE,
                    hapticFeedbackType = HapticFeedbackType.Confirm
                ).takeIf { ready?.hasSavedNote == true },
            isSaving = runningSave != null,
            loadMessage = when (editor) {
                NoteEditor.Loading -> stringRes(R.string.transaction_note_loading)
                NoteEditor.Failed -> stringRes(R.string.transaction_note_load_failed)
                is NoteEditor.Ready -> null
            },
            retry = ButtonState(
                text = stringRes(R.string.transaction_note_retry),
                onClick = ::onRetryLoad,
            ).takeIf { editor == NoteEditor.Failed },
        )
    }

    init {
        loadNote()
    }

    private fun onRetryLoad() {
        if (!dismissed && editor.value == NoteEditor.Failed) loadNote()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadNote() {
        editor.value = NoteEditor.Loading
        loadJob = viewModelScope.launch {
            try {
                val accountUuid = checkNotNull(transactionNote.accountUuid())
                val token = checkNotNull(transactionNote.walletWriteToken)
                val note = getTransactionNote(transactionNote.txId, accountUuid, token)
                editor.value = NoteEditor.Ready(note.orEmpty(), hasSavedNote = note != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.warn(e) { "Unable to load transaction note" }
                editor.value = NoteEditor.Failed
            }
        }
    }

    private fun onAddOrUpdateNoteClick() {
        val ready = editor.value as? NoteEditor.Ready ?: return
        if (ready.text.isBlank() || ready.text.length > MAX_NOTE_LENGTH) return
        runSave(NoteSave.SAVE) {
            createOrUpdateTransactionNote(
                txId = transactionNote.txId,
                note = ready.text,
                accountUuid = checkNotNull(transactionNote.accountUuid()),
                token = checkNotNull(transactionNote.walletWriteToken),
            )
        }
    }

    private fun onDeleteNoteClick() {
        val ready = editor.value as? NoteEditor.Ready ?: return
        if (!ready.hasSavedNote) return
        runSave(NoteSave.DELETE) {
            deleteTransactionNote(
                transactionNote.txId,
                checkNotNull(transactionNote.accountUuid()),
                checkNotNull(transactionNote.walletWriteToken),
            )
        }
    }

    private fun runSave(save: NoteSave, block: suspend () -> Boolean) {
        if (dismissed || runningSave.value != null) return
        runningSave.value = save
        viewModelScope.launch {
            try {
                dismissed = block()
            } finally {
                runningSave.value = null
            }
        }
    }

    private fun onNoteTextChanged(newValue: String) {
        if (dismissed || runningSave.value != null) return
        editor.update { current ->
            if (current is NoteEditor.Ready) current.copy(text = newValue) else current
        }
    }

    /**
     * Ignored while a save runs: the save leaves the sheet itself when it returns, so a back or a
     * dismissal now would make that second back pop the screen underneath. Every dismiss path of
     * the sheet, back, scrim tap and drag down, ends here.
     */
    private fun onBack() {
        if (dismissed || runningSave.value != null) return
        dismissed = true
        loadJob?.cancel()
        navigationRouter.back()
    }
}

private sealed interface NoteEditor {
    data object Loading : NoteEditor

    data object Failed : NoteEditor

    data class Ready(val text: String, val hasSavedNote: Boolean) : NoteEditor
}

private const val MAX_NOTE_LENGTH = 90

/**
 * Which note button started the running save.
 */
private enum class NoteSave {
    SAVE,
    DELETE
}
