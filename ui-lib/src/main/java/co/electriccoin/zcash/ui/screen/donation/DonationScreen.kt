package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.ZashiTextButton
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.donation.AutoDonationStore
import co.electriccoin.zcash.ui.common.donation.AutoDonationSpendingGate
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.ExternalUrl
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.koin.compose.koinInject

@Suppress("MagicNumber", "CyclomaticComplexMethod", "TooGenericExceptionCaught", "LongMethod")
@Composable
fun DonationScreen(args: DonationArgs) {
    val router = koinInject<NavigationRouter>()
    val accounts = koinInject<AccountDataSource>()
    val proposals = koinInject<ZashiProposalRepository>()
    val biometrics = koinInject<BiometricRepository>()
    val context = LocalContext.current
    val preferences = remember(context) { DonationPreferences(context) }
    val automatic = remember(context) { AutoDonationStore(context) }
    val scope = rememberCoroutineScope()
    val account by accounts.selectedAccount.collectAsState(initial = null)
    val accountId = account?.sdkAccount?.accountUuid?.toString()
    val basis = args.basisZec?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    var consent by remember { mutableStateOf(automatic.consent()) }
    var enabled by rememberSaveable(accountId) {
        mutableStateOf(consent?.accountUuid == accountId && accountId != null)
    }
    var percent by rememberSaveable(accountId) {
        mutableStateOf(consent?.takeIf { it.accountUuid == accountId }?.percent?.toPlainString() ?: "0.25")
    }
    var amount by rememberSaveable {
        mutableStateOf(
            basis?.let { BigDecimal.valueOf(DonationPolicy.suggestedZatoshi(it, preferences.percent), 8) }
                ?.takeIf { it.signum() > 0 }?.toPlainString().orEmpty()
        )
    }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var unsupported by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    LaunchedEffect(accountId) {
        consent = automatic.consent()
        enabled = consent?.accountUuid == accountId && accountId != null
        percent = consent?.takeIf { it.accountUuid == accountId }?.percent?.toPlainString() ?: "0.25"
    }
    val percentage = percent.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal(100) }
    val zatoshi = amount.toBigDecimalOrNull()?.let {
        runCatching { it.movePointRight(8).longValueExact() }.getOrNull()?.takeIf { value -> value > 0 }
    }
    val saved = consent?.takeIf { it.accountUuid == accountId }
    val changed = enabled && (saved == null || saved.percent.compareTo(percentage ?: BigDecimal.ZERO) != 0)
    DonationView(
        state = DonationViewState(
            automationEnabled = enabled,
            percent = percent,
            amount = amount,
            busy = busy,
            error = failed,
            unsupported = unsupported || (account != null && account !is ZodlAccount),
            canReview = zatoshi != null && DonationPolicy.recipient() != null && account is ZodlAccount,
            canSaveAuto = changed && percentage != null && account is ZodlAccount,
            automationSaved = saved != null && !changed && enabled,
            autoOutcome = if (saveFailed) context.getString(R.string.private_support_auto_save_failed)
                else automatic.latestOutcome(),
        ),
        onToggleAuto = { value ->
            enabled = value
            failed = false
            if (!value) {
                busy = true
                scope.launch {
                    try {
                        AutoDonationSpendingGate.mutex.withLock { automatic.disable() }
                        consent = null
                    } catch (_: Exception) {
                        saveFailed = true
                        consent = automatic.consent()
                        enabled = consent?.accountUuid == accountId
                    } finally {
                        busy = false
                    }
                }
            }
        },
        onPercentChanged = { percent = it },
        onAmountChanged = { amount = it; failed = false },
        onSaveAuto = {
            if (!busy && percentage != null && account is ZodlAccount) {
                val approvedAccount = requireNotNull(accountId)
                val approvedPercent = percentage
                busy = true
                scope.launch {
                    try {
                        biometrics.requestBiometrics(
                            BiometricRequest(
                                stringRes(R.string.private_support_auto_authorize, approvedPercent.toPlainString())
                            )
                        )
                        val current = accounts.getSelectedAccount()
                        check(current is ZodlAccount && current.sdkAccount.accountUuid.toString() == approvedAccount)
                        AutoDonationSpendingGate.mutex.withLock {
                            val selected = accounts.getSelectedAccount()
                            check(selected is ZodlAccount &&
                                selected.sdkAccount.accountUuid.toString() == approvedAccount)
                            automatic.authorize(approvedAccount, approvedPercent)
                        }
                        consent = automatic.consent()
                        saveFailed = false
                        preferences.postpone(System.currentTimeMillis())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        saveFailed = true
                        consent = automatic.consent()
                        enabled = consent?.accountUuid == accountId
                    } finally {
                        busy = false
                    }
                }
            }
        },
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
            AutoDonationStore(context).consent() == null && preferences.mayInvite(System.currentTimeMillis())
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
