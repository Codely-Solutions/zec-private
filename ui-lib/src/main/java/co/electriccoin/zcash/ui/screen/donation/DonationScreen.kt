package co.electriccoin.zcash.ui.screen.donation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.screen.ExternalUrl
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Suppress("MagicNumber", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
fun DonationScreen(args: DonationArgs) {
    val router = koinInject<NavigationRouter>()
    val accounts = koinInject<AccountDataSource>()
    val proposals = koinInject<ZashiProposalRepository>()
    val context = LocalContext.current
    val preferences = remember(context) { DonationPreferences(context) }
    val scope = rememberCoroutineScope()
    val basis = args.basisZec?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    var percentage by rememberSaveable { mutableStateOf(preferences.percent.toPlainString()) }
    var amount by rememberSaveable {
        mutableStateOf(
            basis?.let { BigDecimal.valueOf(DonationPolicy.suggestedZatoshi(it, preferences.percent), 8) }
                ?.takeIf { it.signum() > 0 }?.toPlainString().orEmpty()
        )
    }
    var busy by remember { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var unsupported by rememberSaveable { mutableStateOf(false) }
    val parsedPercent = percentage.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal(100) }
    val zatoshi = amount.toBigDecimalOrNull()?.let { value ->
        runCatching { value.movePointRight(8).longValueExact() }.getOrNull()?.takeIf { it > 0 }
    }
    BackHandler(enabled = !busy) { router.back() }
    BlankBgScaffold(
        topBar = {
            ZashiSmallTopAppBar(
                title = stringResource(R.string.private_support_title),
                navigationAction = { ZashiTopAppBarBackNavigation(onBack = { if (!busy) router.back() }) }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.private_support_description))
            Text(stringResource(R.string.private_support_privacy), Modifier.padding(vertical = 12.dp))
            TextButton(onClick = { router.forward(ExternalUrl(DonationPolicy.DOCUMENT)) }) {
                Text(stringResource(R.string.private_support_addresses))
            }
            Text(stringResource(R.string.private_support_suggestion))
            // Each choice remains a suggestion; changing it cannot create a proposal or transfer.
            Row {
                listOf("0", "0.25", "0.5", "1").forEach { choice ->
                    TextButton(enabled = !busy, onClick = {
                        percentage = choice
                        preferences.percent = choice.toBigDecimal()
                        amount = basis?.let {
                            BigDecimal.valueOf(DonationPolicy.suggestedZatoshi(it, choice.toBigDecimal()), 8)
                                .takeIf { value -> value.signum() > 0 }?.toPlainString()
                        }.orEmpty()
                    }) { Text("$choice%") }
                }
            }
            OutlinedTextField(
                value = percentage,
                enabled = !busy,
                onValueChange = { value ->
                    percentage = value
                    value.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal(100) }?.let {
                        preferences.percent = it
                        amount = basis?.let { basisValue ->
                            BigDecimal.valueOf(DonationPolicy.suggestedZatoshi(basisValue, it), 8)
                                .takeIf { value -> value.signum() > 0 }?.toPlainString()
                        }.orEmpty()
                    }
                },
                label = { Text(stringResource(R.string.private_support_custom_percent)) },
                isError = parsedPercent == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            basis?.let { Text(stringResource(R.string.private_support_basis, it.toPlainString())) }
            OutlinedTextField(
                value = amount,
                enabled = !busy,
                onValueChange = { amount = it; failed = false },
                label = { Text(stringResource(R.string.private_support_amount)) },
                isError = amount.isNotEmpty() && zatoshi == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Text(stringResource(R.string.private_support_fee), Modifier.padding(vertical = 12.dp))
            if (failed) Text(stringResource(R.string.private_support_failed))
            if (unsupported) Text(stringResource(R.string.private_support_software_only))
            ZashiButton(
                text = stringResource(R.string.private_support_review),
                isLoading = busy,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && zatoshi != null && parsedPercent != null &&
                    DonationPolicy.recipient() != null,
                onClick = {
                    if (!busy) {
                        busy = true
                        failed = false
                        unsupported = false
                        scope.launch {
                            try {
                                val account = accounts.getSelectedAccount()
                                if (account !is ZodlAccount) {
                                    unsupported = true
                                } else {
                                    val send = ZecSend(
                                        WalletAddress.Unified.new(requireNotNull(DonationPolicy.recipient())),
                                        Zatoshi(requireNotNull(zatoshi)), Memo(""), null
                                    )
                                    // Suppress further prompts even if review is cancelled or outcome is uncertain.
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
                }
            )
        }
    }
}

@Suppress("MagicNumber")
@Composable
fun DonationInvitation(basisZec: String?) {
    val context = LocalContext.current
    val router = koinInject<NavigationRouter>()
    val preferences = remember(context) { DonationPreferences(context) }
    var visible by remember { mutableStateOf(preferences.mayInvite(System.currentTimeMillis())) }
    if (visible) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(stringResource(R.string.private_support_invitation))
            TextButton(onClick = {
                preferences.postpone(System.currentTimeMillis())
                visible = false
                router.forward(DonationArgs(basisZec))
            }) { Text(stringResource(R.string.private_support_title)) }
            TextButton(onClick = { preferences.postpone(System.currentTimeMillis()); visible = false }) {
                Text(stringResource(R.string.private_support_not_now))
            }
            TextButton(onClick = { preferences.declinePermanently(); visible = false }) {
                Text(stringResource(R.string.private_support_never))
            }
        }
    }
}
