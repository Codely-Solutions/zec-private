package co.electriccoin.zcash.ui.screen.connecthw.date

import android.app.Application
import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.SdkSynchronizer
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.usecase.CreateHWWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyContent
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.fixture.WalletFixture
import co.electriccoin.zcash.ui.screen.common.BirthdayPickerState
import co.electriccoin.zcash.ui.screen.connecthw.brandingOf
import co.electriccoin.zcash.ui.screen.connecthw.observeEnrollmentFinalization
import co.electriccoin.zcash.ui.screen.connecthw.estimation.HWEstimationArgs
import co.electriccoin.zcash.ui.screen.connecthw.height.HWHeightArgs
import co.electriccoin.zcash.ui.screen.heightinfo.HeightInfoArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import java.time.YearMonth
import java.time.ZoneId
import kotlin.time.toKotlinInstant
import co.electriccoin.zcash.ui.design.R as DesignR

/**
 * Picks the month the enrolled account first transacted in, and estimates the birthday height from
 * it. The picker starts on Sapling activation for every enrollment. The heading and the text under
 * it come from the branding when it sets them, as a view-only import does.
 */
class HWDateVM(
    private val args: HWDateArgs,
    private val createHWWalletAccount: CreateHWWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
    private val application: Application,
    private val errorStateMapper: ErrorMapperUseCase,
    viewOnlyContent: ViewOnlyContent,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    override fun onCleared() {
        navigationRouter.discardResultFrom(sourceEntryId)
        super.onCleared()
    }
    private val branding = brandingOf(args.enrollment, viewOnlyContent)
    private val selection = MutableStateFlow(WalletFixture.SAPLING_ACTIVATION_YEAR_MONTH)
    private val estimateLce = mutableLce<Unit>()

    internal val finalization =
        observeEnrollmentFinalization(args.enrollment, createHWWalletAccount, estimateLce, sourceEntryId)

    val state: StateFlow<LceState<BirthdayPickerState>> =
        combine(selection, estimateLce.state) { yearMonth, estimate ->
            createState(yearMonth, estimate.loading)
        }.withLce(estimateLce, errorStateMapper::mapToState)
            .stateIn(this, LceState(content = createState(selection.value, false)))

    init {
        if (!createHWWalletAccount.isReady(args.enrollment)) {
            navigationRouter.backToRoot()
        }
    }

    private fun createState(yearMonth: YearMonth, isLoading: Boolean) =
        BirthdayPickerState(
            title = null,
            subtitle = branding.dateTitle ?: stringRes(R.string.restoreWallet_birthday_estimateDate_title),
            message = branding.dateSubtitle ?: stringRes(R.string.firstWalletTransactionSubtitleHWWallet),
            logo = branding.logo,
            selection = yearMonth,
            primaryButton =
                ButtonState(
                    text = stringRes(R.string.general_next),
                    isLoading = isLoading,
                    isEnabled = !isLoading,
                    onClick = { onEstimateClick(yearMonth) },
                ),
            secondaryButton =
                ButtonState(
                    text = branding.enterBlockHeightManually,
                    onClick = ::onEnterBlockHeightClick,
                ),
            dialogButton =
                IconButtonState(
                    icon = R.drawable.ic_help,
                    contentDescription = stringRes(DesignR.string.accessibility_wallet_birthday_help),
                    onClick = ::onInfoClick,
                ),
            onBack = ::onBack,
            onYearMonthChange = ::onYearMonthChange,
            secondaryButtonTestTag = branding.enterManuallyTestTag,
        )

    private fun onEstimateClick(yearMonth: YearMonth) =
        estimateLce.guardLoading {
            if (createHWWalletAccount.committedBirthday(args.enrollment) != null) return@guardLoading
            estimateLce.execute { estimateBirthday(yearMonth) }
        }

    private suspend fun estimateBirthday(yearMonth: YearMonth) {
        if (createHWWalletAccount.committedBirthday(args.enrollment) != null) return
        val instant =
            yearMonth
                .atDay(1)
                .atStartOfDay()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toKotlinInstant()
        val bday =
            SdkSynchronizer.estimateBirthdayHeight(
                context = application,
                date = instant,
                network = VersionInfo.NETWORK,
            )
        if (createHWWalletAccount.committedBirthday(args.enrollment) != null) return
        navigationRouter.stageResultFrom(sourceEntryId) {
            NavigationCommand.Forward(listOf(HWEstimationArgs(args.enrollment, bday.value)))
        }
    }

    private fun onEnterBlockHeightClick() =
        estimateLce.guardLoading {
            if (createHWWalletAccount.committedBirthday(args.enrollment) == null) {
                navigationRouter.forward(HWHeightArgs(args.enrollment))
            }
        }

    private fun onBack() = estimateLce.guardLoading { navigationRouter.back() }

    private fun onInfoClick() {
        if (createHWWalletAccount.committedBirthday(args.enrollment) == null) navigationRouter.forward(HeightInfoArgs)
    }

    private fun onYearMonthChange(yearMonth: YearMonth) {
        if (createHWWalletAccount.committedBirthday(args.enrollment) == null) selection.update { yearMonth }
    }
}
