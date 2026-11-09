package co.electriccoin.zcash.ui.design.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One external-navigation action; the trailing glyph is decorative. */
@Composable
fun ExternalLinkRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        Spacer(Modifier.width(16.dp))
        Text(
            text = "↗",
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
