package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.ZashiTooltipBox
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiIconButton
import co.electriccoin.zcash.ui.design.component.ZashiTextButton
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.withTimeoutOrNull


@Composable
internal fun rememberHomeDonationSupport(blocked: Boolean, onDonate: () -> Unit): HomeDonationSupportState {
    val context = LocalContext.current
    val preferences = remember(context) { DonationPreferences(context) }
    // Snapshot at entry: newly observed success waits until the next home visit.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var eligibleAtEntry by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var introduction by remember { mutableStateOf(false) }
    DisposableEffect(preferences, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    eligibleAtEntry = preferences.mayInviteOnHomeEntry(System.currentTimeMillis())
                    dismissed = false
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    introduction = false
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        val release = preferences.observeSuppression {
            dismissed = true
            introduction = false
        }
        onDispose { release(); lifecycle.removeObserver(observer) }
    }
    val showInvitation = resumed && eligibleAtEntry && !dismissed && !blocked
    LaunchedEffect(showInvitation, blocked, resumed) {
        if (showInvitation) {
            if (preferences.mayInvite(System.currentTimeMillis())) {
                preferences.recordInvitationDisplayed(System.currentTimeMillis())
            } else {
                dismissed = true
            }
        } else if (canIntroduce(resumed, blocked, eligibleAtEntry, dismissed, introduction)) {
            // Keep a successful claim when lifecycle setup restarts this effect.
            introduction = preferences.claimAutomaticIntroduction()
        }
        if (blocked || !resumed) introduction = false
    }
    return HomeDonationSupportState(
        showInvitation = showInvitation,
        showIntroduction = introduction && !blocked && !showInvitation,
        onDonate = {
            dismissed = true
            introduction = false
            preferences.postpone(System.currentTimeMillis())
            onDonate()
        },
        onLater = {
            preferences.postpone(System.currentTimeMillis())
            dismissed = true
            introduction = false
        },
        onNever = {
            preferences.declinePermanently()
            dismissed = true
            introduction = false
        },
    )
}

private fun canIntroduce(
    resumed: Boolean,
    blocked: Boolean,
    invitationEligible: Boolean,
    dismissed: Boolean,
    alreadyIntroduced: Boolean,
): Boolean {
    if (alreadyIntroduced) return false
    return resumed && !blocked && !invitationEligible && !dismissed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DonationShortcut(state: HomeDonationSupportState) {
    val tooltip = rememberTooltipState(isPersistent = true)
    LaunchedEffect(state.showIntroduction) {
        if (state.showIntroduction) {
            try {
                withTimeoutOrNull(INTRODUCTION_MILLIS) { tooltip.show() }
            } finally {
                tooltip.dismiss()
            }
        }
    }
    ZashiTooltipBox(
        state = tooltip,
        focusable = false,
        tooltip = {
            PlainTooltip {
                Text(stringResource(R.string.private_support_title), style = ZashiTypography.textSm)
            }
        },
        anchor = {
            ZashiIconButton(
                state = IconButtonState(
                    icon = R.drawable.ic_support_hand_heart,
                    contentDescription = stringRes(R.string.private_support_title),
                    onClick = { tooltip.dismiss(); state.onDonate() },
                ),
                modifier = Modifier.size(40.dp),
                iconTint = ZashiColors.Text.textPrimary,
            )
        },
    )
}

@Composable
internal fun HomeDonationInvitation(state: HomeDonationSupportState) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)
            .fillMaxWidth().background(ZashiColors.Surfaces.bgSecondary, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_support_hand_heart),
                contentDescription = null,
                tint = ZashiColors.Text.textPrimary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                stringResource(R.string.private_support_home_title),
                modifier = Modifier.weight(1f),
                style = ZashiTypography.textMd,
                color = ZashiColors.Text.textPrimary,
            )
            ZashiButton(text = stringResource(R.string.private_support_donate), onClick = state.onDonate)
        }
        Text(
            stringResource(R.string.private_support_home_message),
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textSecondary,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ZashiTextButton(onClick = state.onNever, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.private_support_never))
            }
            ZashiTextButton(onClick = state.onLater, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.private_support_later))
            }
        }
    }
}

private const val INTRODUCTION_MILLIS = 6_000L
