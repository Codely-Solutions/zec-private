package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.runtime.Composable
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.screen.connecthw.CommittedEnrollmentBirthday
import co.electriccoin.zcash.ui.screen.connecthw.EnrollmentFinalizationState
import co.electriccoin.zcash.ui.screen.connecthw.EnrollmentFinalizationView
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.KeystoneQrPreparation
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionState
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionView
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.ZashiAccountInfoListItemState

/** Production components, synthetic account/QR values and inert actions; no device or SDK I/O. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun CorrectionReviewScene(screen: String): Boolean {
    when (screen) {
        "note-save-retry" -> co.electriccoin.zcash.ui.screen.transactionnote.view.TransactionNoteView(
            state = co.electriccoin.zcash.ui.screen.transactionnote.model.TransactionNoteState(
                onBack = {}, title = stringRes(R.string.annotation_edit),
                note = TextFieldState(value = stringRes("Draft retained after failed save"), onValueChange = {}),
                noteCharacters = stringRes(R.string.transaction_note_max_length, "32").withStyle(),
                primaryButton = null,
                secondaryButton = ButtonState(stringRes(R.string.annotation_save), onClick = {}),
                negative = ButtonState(stringRes(R.string.annotation_delete), onClick = {}),
            ),
        )
        "note-loading", "note-load-failed" -> co.electriccoin.zcash.ui.screen.transactionnote.view.TransactionNoteView(
            state = co.electriccoin.zcash.ui.screen.transactionnote.model.TransactionNoteState(
                onBack = {}, title = stringRes(R.string.annotation_addArticle),
                note = TextFieldState(value = stringRes(""), isEnabled = false, onValueChange = {}),
                noteCharacters = stringRes("").withStyle(),
                primaryButton = null, secondaryButton = null, negative = null,
                loadMessage = stringRes(if (screen == "note-loading") R.string.transaction_note_loading else R.string.transaction_note_load_failed),
                retry = ButtonState(stringRes(R.string.transaction_note_retry), onClick = {}).takeIf { screen == "note-load-failed" },
            ),
        )
        "enrollment-committed-height", "enrollment-committed-tip" -> EnrollmentFinalizationView(
            state = EnrollmentFinalizationState(
                birthday = CommittedEnrollmentBirthday(
                    BlockHeight.new(3_000_000).takeIf { screen.endsWith("height") },
                    Account.new(AccountUuid.new(ByteArray(16) { it.toByte() })),
                ),
                finish = ButtonState(stringRes(R.string.enrollment_finish_setup), onClick = {}),
            ),
            onBack = {},
        )
        "keystone-qr-error", "keystone-qr-loading", "keystone-qr-ready" -> {
            val ready = screen == "keystone-qr-ready"
            val error = screen == "keystone-qr-error"
            SignKeystoneTransactionView(SignKeystoneTransactionState(
                onBack = {},
                barTitle = stringRes(R.string.keystone_signWith_signTransaction),
                title = stringRes(R.string.keystone_signWith_title),
                subtitle = stringRes(R.string.keystone_signWith_desc),
                accountInfo = ZashiAccountInfoListItemState(
                    co.electriccoin.zcash.ui.design.R.drawable.ic_item_keystone,
                    stringRes("Keystone"), stringRes("Synthetic account"),
                ),
                badgeText = stringRes(R.string.keystone_signWith_hardware),
                qrData = "synthetic-pczt-qr".takeIf { ready },
                generateNextQrCode = {},
                secondaryButton = null,
                positiveButton = ButtonState(stringRes(R.string.keystone_signWith_getSignature), isEnabled = ready, onClick = {}),
                negativeButton = ButtonState(stringRes(R.string.keystone_signWith_reject), onClick = {}),
                preparation = when { ready -> KeystoneQrPreparation.READY; error -> KeystoneQrPreparation.ERROR; else -> KeystoneQrPreparation.LOADING },
                retryButton = ButtonState(stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain), onClick = {}).takeIf { error },
            ))
        }
        else -> return false
    }
    return true
}
