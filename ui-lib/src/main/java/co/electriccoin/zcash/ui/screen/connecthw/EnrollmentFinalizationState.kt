package co.electriccoin.zcash.ui.screen.connecthw

import co.electriccoin.zcash.ui.design.component.ButtonState

internal data class EnrollmentFinalizationState(
    val birthday: CommittedEnrollmentBirthday,
    val finish: ButtonState,
)
