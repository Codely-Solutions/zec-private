package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.datasource.PaymentDraft
import co.electriccoin.zcash.ui.common.usecase.PreparePaymentReviewUseCase
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.screen.send.model.AmountField
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.MemoState
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.ExternalUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Suppress("MagicNumber", "CyclomaticComplexMethod", "TooGenericExceptionCaught", "LongMethod")
@Composable
fun DonationScreen(@Suppress("UNUSED_PARAMETER") args: DonationArgs, sourceEntryId: String) {
    val router = koinInject<NavigationRouter>()
    val accounts = koinInject<AccountDataSource>()
    val review = koinInject<PreparePaymentReviewUseCase>()
    val rates = koinInject<ExchangeRateRepository>()
    val exchangeRate by rates.state.collectAsState()
    val context = LocalContext.current
    val preferences = remember(context) { DonationPreferences(context) }
    val scope = rememberCoroutineScope()
    val account by accounts.selectedAccount.collectAsState(initial = null)
    var amountState by rememberSaveable(stateSaver = AmountState.getSaver(context)) {
        mutableStateOf(AmountState.newFromZec("", "", false, exchangeRate))
    }
    var memoState by rememberSaveable(stateSaver = MemoState.Saver) { mutableStateOf(MemoState.new("")) }
    var memoExpanded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var unsupported by remember { mutableStateOf(false) }
    LaunchedEffect(rates) { rates.refreshExchangeRateUsd() }
    LaunchedEffect(exchangeRate, busy) {
        if (!busy) {
            amountState = if (amountState.lastFieldChangedByUser == AmountField.FIAT) {
                AmountState.newFromFiat(
                    amountState.value.getString(context), amountState.fiatValue.getString(context), false, exchangeRate
                )
            } else {
                AmountState.newFromZec(
                    amountState.value.getString(context), amountState.fiatValue.getString(context), false, exchangeRate
                )
            }
        }
    }
    val zatoshi = (amountState as? AmountState.Valid)?.zatoshi?.takeIf { it.value > 0 }
    DonationView(
        state = DonationViewState(
            amountState = amountState,
            memoState = memoState,
            memoExpanded = memoExpanded,
            exchangeRateState = exchangeRate,
            spendableShieldedBalance = account?.spendableShieldedBalance,
            busy = busy,
            error = failed,
            unsupported = unsupported || (account != null && account !is ZodlAccount),
            canReview = zatoshi != null && memoState is MemoState.Correct &&
                DonationPolicy.recipient() != null && account is ZodlAccount && account?.canSpend(zatoshi) == true,
        ),
        onAmountChanged = { if (!busy) { amountState = it; failed = false } },
        onMemoChanged = { if (!busy) { memoState = it; failed = false } },
        onToggleMemo = { if (!busy) memoExpanded = !memoExpanded },
        onReview = {
            val reviewedAmount = (amountState as? AmountState.Valid)?.zatoshi?.takeIf { it.value > 0 }
            val reviewedMemo = (memoState as? MemoState.Correct)?.text
            if (!busy && reviewedAmount != null && reviewedMemo != null) {
                busy = true
                failed = false
                unsupported = false
                scope.launch {
                    try {
                        val selected = account as? ZodlAccount
                        if (selected == null) {
                            unsupported = true
                        } else {
                            val owner = review.capture(sourceEntryId, selected.sdkAccount.accountUuid) ?: return@launch
                            val send = ZecSend(
                                WalletAddress.Unified.new(requireNotNull(DonationPolicy.recipient())),
                                reviewedAmount, Memo(reviewedMemo), null
                            )
                            preferences.postpone(System.currentTimeMillis())
                            review.open(owner, PaymentDraft.Donation(send)) { route, _ ->
                                NavigationCommand.Forward(listOf(route))
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
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
