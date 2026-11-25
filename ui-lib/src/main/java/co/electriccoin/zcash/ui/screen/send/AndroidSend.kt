@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.send

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.model.toZecString
import co.electriccoin.zcash.di.koinActivityViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.appbar.ZashiTopAppBarVM
import co.electriccoin.zcash.ui.common.compose.LocalActivity
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.ObserveClearSendUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendData
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.util.StringResource.Companion.NUMBER_FORMAT_LOCALE
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetArgs
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetVM
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import co.electriccoin.zcash.ui.screen.send.ext.Saver
import co.electriccoin.zcash.ui.screen.send.model.AmountField
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.MemoState
import co.electriccoin.zcash.ui.screen.send.model.RecipientAddressState
import co.electriccoin.zcash.ui.screen.send.model.SendStage
import co.electriccoin.zcash.ui.screen.send.view.Send
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
internal fun WrapSend(args: Send, sendEntryId: String) {
    val activity = LocalActivity.current

    val navigationRouter = koinInject<NavigationRouter>()

    val balanceWidgetVM =
        koinViewModel<BalanceWidgetVM> {
            parametersOf(
                BalanceWidgetArgs(
                    isBalanceButtonEnabled = true,
                    isExchangeRateButtonEnabled = false,
                    showDust = true
                )
            )
        }

    val accountDataSource = koinInject<AccountDataSource>()

    val exchangeRateRepository = koinInject<ExchangeRateRepository>()

    val hasCameraFeature = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    val selectedAccount = accountDataSource.selectedAccount.collectAsStateWithLifecycle(null).value

    val balanceState = balanceWidgetVM.state.collectAsStateWithLifecycle().value

    val exchangeRateState = exchangeRateRepository.state.collectAsStateWithLifecycle().value

    WrapSend(
        sendEntryId = sendEntryId,
        balanceWidgetState = balanceState,
        exchangeRateState = exchangeRateState,
        goToQrScanner = {
            navigationRouter.forward(
                ScanArgs(
                    ScanFlow.SEND,
                    isScanZip321Enabled = args.isScanZip321Enabled
                )
            )
        },
        goBack = { navigationRouter.back() },
        hasCameraFeature = hasCameraFeature,
        sendArguments = args,
        selectedAccount = selectedAccount
    )
}

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@VisibleForTesting
@Composable
internal fun WrapSend(
    sendEntryId: String,
    balanceWidgetState: BalanceWidgetState,
    exchangeRateState: ExchangeRateState,
    goToQrScanner: () -> Unit,
    goBack: () -> Unit,
    hasCameraFeature: Boolean,
    sendArguments: Send,
    selectedAccount: WalletAccount?,
) {
    val viewModel = koinViewModel<SendViewModel> { parametersOf(sendEntryId) }

    BackHandler {
        viewModel.cancelProposalPreparation()
        goBack()
    }

    val sendAddressBookState by viewModel.sendAddressBookState.collectAsStateWithLifecycle()

    val topAppBarViewModel = koinActivityViewModel<ZashiTopAppBarVM>()

    val zashiMainTopAppBarState by topAppBarViewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val (sendStage, setSendStage) =
        rememberSaveable(stateSaver = SendStage.Saver) { mutableStateOf(SendStage.Form) }

    val (zecSend, setZecSend) = rememberSaveable(stateSaver = ZecSend.Saver) { mutableStateOf(null) }

    val recipientAddressState = viewModel.recipientAddressState.collectAsStateWithLifecycle().value

    val observeClearSend = koinInject<ObserveClearSendUseCase>()
    val prefillSend = koinInject<PrefillSendUseCase>()

    LaunchedEffect(viewModel, sendArguments) {
        viewModel.initializeRecipient(sendArguments)
    }

    val amountDraftState =
        rememberSaveable(stateSaver = AmountState.getSaver(context)) {
            mutableStateOf(
                AmountState.newFromZec(
                    value = zecSend?.amount?.toZecString(NUMBER_FORMAT_LOCALE) ?: "",
                    fiatValue = "",
                    isTransparentOrTextRecipient = !recipientAddressState.supportsMemo,
                    exchangeRateState = exchangeRateState,
                )
            )
        }
    val (amountDraft, setAmountState) = amountDraftState
    val amountState =
        remember(amountDraft, recipientAddressState, exchangeRateState, context) {
            if (amountDraft.lastFieldChangedByUser == AmountField.ZEC) {
                AmountState.newFromZec(
                    value = amountDraft.value.getString(context),
                    fiatValue = amountDraft.fiatValue.getString(context),
                    isTransparentOrTextRecipient = !recipientAddressState.supportsMemo,
                    exchangeRateState = exchangeRateState,
                    requireFreshRate = true,
                )
            } else {
                AmountState.newFromFiat(
                    value = amountDraft.value.getString(context),
                    fiatValue = amountDraft.fiatValue.getString(context),
                    isTransparentOrTextRecipient = !recipientAddressState.supportsMemo,
                    exchangeRateState = exchangeRateState,
                    fiatCurrency = amountDraft.fiatCurrency,
                    requireFreshRate = true,
                )
            }
        }

    // Memo computation:
    val memoDraftState =
        rememberSaveable(stateSaver = MemoState.Saver) {
            mutableStateOf(MemoState.new(zecSend?.memo?.value ?: ""))
        }
    val (memoState, setMemoState) = memoDraftState

    LaunchedEffect(Unit) {
        observeClearSend().collect {
            setSendStage(SendStage.Form)
            setZecSend(null)
            viewModel.onRecipientAddressChanged(RecipientAddressState.new("", null))
            setAmountState(
                AmountState.newFromZec(
                    value = "",
                    fiatValue = "",
                    isTransparentOrTextRecipient = false,
                    exchangeRateState = exchangeRateState,
                )
            )
            setMemoState(MemoState.new(""))
        }
    }

    LaunchedEffect(Unit) {
        prefillSend().collect {
            when (it) {
                is PrefillSendData.All -> {
                    setSendStage(SendStage.Form)
                    setZecSend(null)
                    viewModel.validateRecipient(it.address.orEmpty())

                    val fee = it.fee
                    val value =
                        when {
                            fee == null -> it.amount
                            fee > it.amount -> it.amount
                            else -> it.amount - fee
                        }

                    setAmountState(
                        AmountState.newFromZec(
                            value = stringRes(value, TickerLocation.HIDDEN).getString(context),
                            fiatValue = "",
                            isTransparentOrTextRecipient = true,
                            exchangeRateState = exchangeRateState,
                        )
                    )
                    setMemoState(MemoState.new(it.memos?.firstOrNull().orEmpty()))
                }

                is PrefillSendData.FromAddressScan -> {
                    setSendStage(SendStage.Form)
                    setZecSend(null)
                    viewModel.validateRecipient(it.address)
                }
            }
        }
    }

    val onBackAction = {
        when (sendStage) {
            SendStage.Form -> {
                viewModel.cancelProposalPreparation()
                goBack()
            }

            SendStage.Proposing -> {
                // no action - wait until the sending is done
            }

            is SendStage.SendFailure -> {
                setSendStage(SendStage.Form)
            }
        }
    }

    if (null == selectedAccount) {
        // TODO [#1146]: Consider moving CircularScreenProgressIndicator from Android layer to View layer
        // TODO [#1146]: Improve this by allowing screen composition and updating it after the data is available
        // TODO [#1146]: https://github.com/Electric-Coin-Company/zashi-android/issues/1146
        CircularScreenProgressIndicator()
    } else {
        Send(
            balanceWidgetState = balanceWidgetState,
            sendStage = sendStage,
            onCreateZecSend = { newZecSend ->
                if (amountDraftState.value != amountDraft || memoDraftState.value != memoState) {
                    return@Send
                }
                viewModel.onCreateZecSendClick(
                    newZecSend = newZecSend,
                    amountState = amountState,
                    renderedRecipient = recipientAddressState,
                    setSendStage = setSendStage
                )
            },
            onBack = onBackAction,
            onQrScannerOpen = {
                viewModel.cancelProposalPreparation()
                goToQrScanner()
            },
            hasCameraFeature = hasCameraFeature,
            recipientAddressState = recipientAddressState,
            onRecipientAddressChange = viewModel::validateRecipient,
            setAmountState = {
                if (amountDraftState.value != it) viewModel.cancelProposalPreparation()
                setAmountState(it)
            },
            amountState = amountState,
            setMemoState = {
                if (memoDraftState.value != it) viewModel.cancelProposalPreparation()
                setMemoState(it)
            },
            memoState = memoState,
            selectedAccount = selectedAccount,
            exchangeRateState = exchangeRateState,
            sendAddressBookState = sendAddressBookState,
            zashiMainTopAppBarState = zashiMainTopAppBarState
        )
    }
}
