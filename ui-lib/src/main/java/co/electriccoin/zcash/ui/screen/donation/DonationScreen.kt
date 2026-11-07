package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.design.component.ZashiTextButton
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.ExternalUrl
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Suppress("MagicNumber", "CyclomaticComplexMethod", "TooGenericExceptionCaught", "LongMethod")
@Composable
fun DonationScreen(@Suppress("UNUSED_PARAMETER") args: DonationArgs) {
    val router = koinInject<NavigationRouter>()
    val accounts = koinInject<AccountDataSource>()
    val proposals = koinInject<ZashiProposalRepository>()
    val context = LocalContext.current
    val preferences = remember(context) { DonationPreferences(context) }
    val scope = rememberCoroutineScope()
    val account by accounts.selectedAccount.collectAsState(initial = null)
    var amount by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var unsupported by remember { mutableStateOf(false) }
    val zatoshi = amount.toBigDecimalOrNull()?.let {
        runCatching { it.movePointRight(8).longValueExact() }.getOrNull()?.takeIf { value -> value > 0 }
    }
    DonationView(
        state = DonationViewState(
            amount = amount,
            busy = busy,
            error = failed,
            unsupported = unsupported || (account != null && account !is ZodlAccount),
            canReview = zatoshi != null && DonationPolicy.recipient() != null && account is ZodlAccount,
        ),
        onAmountChanged = { amount = it; failed = false },
        onReview = {
            if (!busy && zatoshi != null) {
                busy = true
                failed = false
                unsupported = false
                scope.launch {
                    try {
                        val selected = accounts.getSelectedAccount()
                        if (selected !is ZodlAccount) {
                            unsupported = true
                        } else {
                            val send = ZecSend(
                                WalletAddress.Unified.new(requireNotNull(DonationPolicy.recipient())),
                                Zatoshi(zatoshi), Memo(""), null
                            )
                            preferences.postpone(System.currentTimeMillis())
                            proposals.createDonationProposal(send)
                            router.forward(ReviewTransactionArgs)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        proposals.clear()
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }
        },
        onExternalAddresses = { router.forward(ExternalUrl(DonationPolicy.DOCUMENT)) },
        onBack = { if (!busy) router.back() }
    )
}

@Suppress("MagicNumber")
@Composable
fun DonationInvitation(basisZec: String?) {
    val context = LocalContext.current
    val router = koinInject<NavigationRouter>()
    val preferences = remember(context) { DonationPreferences(context) }
    var visible by remember {
        mutableStateOf(
            preferences.mayInvite(System.currentTimeMillis())
        )
    }
    if (visible) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.private_support_invitation),
                style = ZashiTypography.textMd, color = ZashiColors.Text.textPrimary
            )
            ZashiTextButton(onClick = {
                preferences.postpone(System.currentTimeMillis())
                visible = false
                router.forward(DonationArgs(basisZec))
            }) { Text(stringResource(R.string.private_support_title)) }
            ZashiTextButton(onClick = { preferences.postpone(System.currentTimeMillis()); visible = false }) {
                Text(stringResource(R.string.private_support_not_now))
            }
            ZashiTextButton(onClick = { preferences.declinePermanently(); visible = false }) {
                Text(stringResource(R.string.private_support_never))
            }
        }
    }
}
