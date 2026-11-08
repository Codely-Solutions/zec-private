package co.electriccoin.zcash.ui.screen.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiTextButton

@Composable
internal fun AppUpdateContent(
    checking: Boolean,
    current: Boolean,
    failed: Boolean,
    availableTag: String?,
    onCheck: () -> Unit,
    onOpenRelease: () -> Unit,
    onObtainium: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.private_updates_title), style = MaterialTheme.typography.titleMedium)
        when {
            availableTag != null -> Text(stringResource(R.string.private_updates_available, availableTag))
            checking -> Text(stringResource(R.string.private_updates_checking))
            failed -> Text(stringResource(R.string.private_updates_failed), color = MaterialTheme.colorScheme.error)
            current -> Text(stringResource(R.string.private_updates_current))
        }
        if (availableTag != null) {
            ZashiButton(text = stringResource(R.string.private_updates_view), onClick = onOpenRelease)
        }
        ZashiTextButton(onClick = onCheck, enabled = !checking) {
            Text(stringResource(R.string.private_updates_check))
        }
        ZashiTextButton(onClick = onObtainium) {
            Text(stringResource(R.string.private_updates_obtainium))
        }
    }
}

@Composable
internal fun AppUpdateNotice(tag: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.private_updates_available, tag), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZashiButton(text = stringResource(R.string.private_updates_view), onClick = onOpen)
            ZashiTextButton(onClick = onDismiss) { Text(stringResource(R.string.private_updates_dismiss)) }
        }
    }
}
