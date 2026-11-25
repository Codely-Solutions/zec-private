package co.electriccoin.zcash.ui.screen.migration.complete

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.migration.component.MigrationUnavailable
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.component.ZashiIconButton
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.rememberZashiFrostState
import co.electriccoin.zcash.ui.design.component.zashiFrostSource
import co.electriccoin.zcash.ui.design.component.zashiFrostedHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import co.electriccoin.zcash.ui.screen.common.PrivacyDisclaimerCard
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import co.electriccoin.zcash.ui.design.R as DesignR

data class MigrationCompleteState(
    val totalTransferred: StringResource?,
    val remainingDust: StringResource?,
    val lockNotice: StringResource? = null,
    val transfersProgress: StringResource?,
    val duration: StringResource?,
    val onDone: () -> Unit,
    val onMigrateAnyway: () -> Unit,
    val onLockBalance: () -> Unit,
    val onHelp: () -> Unit,
    val canShowCompletion: Boolean = true,
    val canMigrateResidue: Boolean = true,
    val isMigrating: Boolean = false,
    val isLocking: Boolean = false,
    // MOB-1750: true for a small leftover Orchard balance not tied to an unseen in-app migration
    // celebration (no Transfers/Duration rows) — false for the
    // original post-migration celebration variant ("Migration Complete"), unchanged.
    val isResidueOnly: Boolean = false,
)

@Serializable
data class MigrationCompleteArgs(
    val isResidueOnly: Boolean = false,
    val accountKeyId: String? = null
)

@Composable
fun MigrationCompleteScreen(args: MigrationCompleteArgs, sourceEntryId: String) {
    val vm = koinViewModel<MigrationCompleteVM> { parametersOf(args, sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(
        state = state,
        loading = { isLoading -> if (isLoading && state.content == null) CircularScreenProgressIndicator() },
    ) { content ->
        if (content.canShowCompletion) {
            MigrationCompleteView(content)
        } else {
            MigrationUnavailable(
                message = stringRes(DesignR.string.migrationProgress_unavailable),
                onBack = content.onDone,
                onRetry = content.onDone,
                retryLabel = stringRes(co.electriccoin.zcash.ui.R.string.general_back),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationCompleteView(state: MigrationCompleteState) {
    val hazeState = rememberZashiFrostState()
    BlankBgScaffold(
        topBar = {
            ZashiSmallTopAppBar(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .zashiFrostedHeader(hazeState),
                colors =
                    ZcashTheme.colors.topAppBarColors.copyColors(
                        containerColor = Color.Transparent
                    ),
                // Explain the account-wide lock when the wallet reports spendable Orchard funds.
                regularActions = {
                    if (state.remainingDust != null) {
                        ZashiIconButton(
                            state =
                                IconButtonState(
                                    icon = R.drawable.ic_help,
                                    contentDescription = stringRes(DesignR.string.migrationLockExplainer_title),
                                    onClick = state.onHelp,
                                ),
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(20.dp))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .zashiFrostSource(hazeState)
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .scaffoldPadding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                Image(
                    painter = painterResource(co.electriccoin.zcash.migration.R.drawable.ic_migration_complete),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text =
                        stringRes(
                            if (state.isResidueOnly) {
                                DesignR.string.migrationComplete_residueTitle
                            } else {
                                DesignR.string.migrationComplete_title
                            }
                        ).getValue(),
                    style = ZashiTypography.header5,
                    fontWeight = FontWeight.SemiBold,
                    color = ZashiColors.Text.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        stringRes(
                            if (state.isResidueOnly) {
                                DesignR.string.migrationComplete_residueSubtitle
                            } else {
                                DesignR.string.migrationComplete_subtitle
                            }
                        ).getValue(),
                    style = ZashiTypography.textSm,
                    color = ZashiColors.Text.textTertiary,
                    textAlign = TextAlign.Center,
                )
                SummaryCard(state)
                Spacer(Modifier.weight(1f))
                state.lockNotice?.let { notice ->
                    LockedDisclaimer(notice)
                    Spacer(Modifier.height(16.dp))
                }
                when {
                    state.remainingDust == null -> {
                        ZashiButton(
                            state =
                                ButtonState(
                                    text = stringRes(DesignR.string.migration_common_gotIt),
                                    onClick = state.onDone
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    else -> {
                        Spacer(Modifier.height(20.dp))
                        PrivacyDisclaimerCard(
                            title = stringRes(DesignR.string.migrationComplete_orchardBalanceRemainingTitle).getValue(),
                            body =
                                stringRes(
                                    if (state.isResidueOnly) {
                                        DesignR.string.migrationComplete_residueRecommendationBody
                                    } else {
                                        DesignR.string.migrationComplete_orchardBalanceRemainingBody
                                    },
                                    state.remainingDust.getValue()
                                ).getValue(),
                        )
                        Spacer(Modifier.height(24.dp))
                        if (state.canMigrateResidue) {
                            ZashiButton(
                                state =
                                    ButtonState(
                                        text =
                                            stringRes(
                                                if (state.isMigrating) {
                                                    DesignR.string.migrationComplete_migrating
                                                } else {
                                                    DesignR.string.migrationComplete_migrateAnyway
                                                }
                                            ),
                                        onClick = state.onMigrateAnyway,
                                        isEnabled = !state.isMigrating && !state.isLocking,
                                        isLoading = state.isMigrating,
                                    ),
                                modifier = Modifier.fillMaxWidth(),
                                defaultPrimaryColors =
                                    ZashiButtonDefaults.secondaryColors(
                                        contentColor = ZashiColors.Utility.WarningYellow.utilityOrange700,
                                        borderColor = ZashiColors.Utility.WarningYellow.utilityOrange300,
                                    ),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        ZashiButton(
                            state =
                                ButtonState(
                                    text =
                                        stringRes(
                                            if (state.isLocking) {
                                                DesignR.string.migrationComplete_lockingBalance
                                            } else {
                                                DesignR.string.migrationComplete_lockBalance
                                            }
                                        ),
                                    onClick = state.onLockBalance,
                                    isEnabled = !state.isMigrating && !state.isLocking,
                                    isLoading = state.isLocking,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: MigrationCompleteState) {
    if (state.totalTransferred == null && state.remainingDust == null && state.transfersProgress == null) return
    Spacer(Modifier.height(24.dp))
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ZashiColors.Surfaces.bgSecondary)
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.totalTransferred?.let { amount ->
            val label = if (state.isResidueOnly) {
                DesignR.string.migrationComplete_inIronwoodLabel
            } else {
                DesignR.string.migrationComplete_totalTransferredLabel
            }
            SummaryRow(label = stringRes(label).getValue(), value = amount.getValue())
        }
        state.remainingDust?.let { dust ->
            val label = if (state.isResidueOnly) {
                DesignR.string.migrationComplete_leftInOrchardLabel
            } else {
                DesignR.string.migrationComplete_remainingDustLabel
            }
            SummaryRow(label = stringRes(label).getValue(), value = dust.getValue())
        }
        state.transfersProgress?.let {
            SummaryRow(
                label = stringRes(DesignR.string.migrationComplete_transfersLabel).getValue(),
                value = it.getValue(),
            )
        }
        state.duration?.let {
            SummaryRow(
                label = stringRes(DesignR.string.migrationComplete_durationLabel).getValue(),
                value = it.getValue(),
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = ZashiTypography.textSm,
            fontWeight = FontWeight.Medium,
            color = ZashiColors.Text.textPrimary,
        )
    }
}

@Composable
private fun LockedDisclaimer(notice: StringResource) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ZashiColors.Surfaces.bgSecondary)
                .padding(16.dp),
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = notice.getValue(),
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textPrimary,
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
            contentDescription = null,
            tint = ZashiColors.Text.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewWithDust() =
    ZcashTheme {
        MigrationCompleteView(
            state =
                MigrationCompleteState(
                    totalTransferred = stringRes("12.458 ZEC"),
                    remainingDust = stringRes("0.00031 ZEC"),
                    transfersProgress = stringRes("5 of 5 sent"),
                    duration = stringRes("~24 hours"),
                    onDone = {},
                    onMigrateAnyway = {},
                    onLockBalance = {},
                    onHelp = {},
                )
        )
    }

@PreviewScreens
@Composable
private fun PreviewWithLockedDust() =
    ZcashTheme {
        MigrationCompleteView(
            state =
                MigrationCompleteState(
                    totalTransferred = stringRes("12.458 ZEC"),
                    remainingDust = stringRes("0.00031 ZEC"),
                    lockNotice = stringRes(DesignR.string.migrationComplete_lockRequestCompleted),
                    transfersProgress = stringRes("5 of 5 sent"),
                    duration = stringRes("~24 hours"),
                    onDone = {},
                    onMigrateAnyway = {},
                    onLockBalance = {},
                    onHelp = {},
                )
        )
    }

@PreviewScreens
@Composable
private fun PreviewNoDust() =
    ZcashTheme {
        MigrationCompleteView(
            state =
                MigrationCompleteState(
                    totalTransferred = stringRes("12.458 ZEC"),
                    remainingDust = null,
                    transfersProgress = stringRes("5 of 5 sent"),
                    duration = stringRes("~24 hours"),
                    onDone = {},
                    onMigrateAnyway = {},
                    onLockBalance = {},
                    onHelp = {},
                )
        )
    }

@PreviewScreens
@Composable
private fun PreviewResidueOnly() =
    ZcashTheme {
        MigrationCompleteView(
            state =
                MigrationCompleteState(
                    totalTransferred = stringRes("12.45 ZEC"),
                    remainingDust = stringRes("0.008 ZEC"),
                    transfersProgress = stringRes("0 of 0 sent"),
                    duration = stringRes("~0 hours"),
                    isResidueOnly = true,
                    onDone = {},
                    onMigrateAnyway = {},
                    onLockBalance = {},
                    onHelp = {},
                )
        )
    }
