package co.electriccoin.zcash.ui.screen.donation

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.MemoState

/** Non-shipping, synthetic design proposal. No wallet, account, network or payment dependencies. */
class MaterialPreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG)
        enableEdgeToEdge()
        val initial = intent.getStringExtra("screen") ?: "home"
        val dynamic = intent.getBooleanExtra("dynamic", true)
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors, typography = Typography(), shapes = Shapes()) {
                var screen by remember { mutableStateOf(initial) }
                BackHandler(screen != "home") { screen = "home" }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(when (screen) {
                                "send" -> "Send Zcash"
                                "donation" -> "Support development"
                                else -> "ZEC Private"
                            }) },
                            navigationIcon = {
                                if (screen != "home") IconButton(onClick = { screen = "home" }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                                }
                            },
                            actions = {
                                if (screen == "home") {
                                    IconButton(onClick = { screen = "donation" }) {
                                        Icon(painterResource(R.drawable.ic_support_hand_heart), "Support development")
                                    }
                                    IconButton(onClick = {}) { Icon(Icons.Outlined.Visibility, "Hide balances") }
                                    IconButton(onClick = {}) { Icon(Icons.Outlined.MoreVert, "More") }
                                }
                            }
                        )
                    }
                ) { insets ->
                    Column(
                        Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Text("Design preview · sample data", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (screen) {
                            "send", "donation" -> PaymentPreview(screen == "donation")
                            else -> HomePreview(dark, { screen = "send" }, { screen = "donation" })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomePreview(dark: Boolean, onSend: () -> Unit, onDonate: () -> Unit) {
    var invitation by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("0.125 ZEC", style = MaterialTheme.typography.displayMedium)
        Text("\$156.25", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeAction("Receive", Icons.Outlined.ArrowDownward, Modifier.weight(1f)) {}
        HomeAction("Send", Icons.Outlined.ArrowUpward, Modifier.weight(1f), onSend)
        HomeAction("Pay", Icons.Outlined.ArrowForward, Modifier.weight(1f)) {}
        HomeAction("Swap", Icons.Outlined.SwapVert, Modifier.weight(1f)) {}
    }
    if (invitation) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Support ZEC Private", style = MaterialTheme.typography.titleMedium)
                Text("Help fund continued development and maintenance.", style = MaterialTheme.typography.bodyMedium)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { invitation = false }) { Text("Don’t ask again") }
                    TextButton(onClick = { invitation = false }) { Text("Later") }
                    Button(onClick = onDonate) { Text("Donate") }
                }
            }
        }
    }
    Text("Activity", style = MaterialTheme.typography.titleLarge)
    val received = if (dark) Color(0xFF7BDB9A) else Color(0xFF006D3B)
    ListItem(
        headlineContent = { Text("Received") }, supportingContent = { Text("Yesterday") },
        leadingContent = { Icon(Icons.Outlined.ArrowDownward, null) },
        trailingContent = { Text("+0.01 ZEC", color = received) }
    )
    HorizontalDivider()
    ListItem(
        headlineContent = { Text("Sent") }, supportingContent = { Text("2 days ago") },
        leadingContent = { Icon(Icons.Outlined.ArrowUpward, null) },
        trailingContent = { Text("−0.002 ZEC") }
    )
}

@Composable
private fun HomeAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null)
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun PaymentPreview(donation: Boolean) {
    var amount by remember { mutableStateOf("0.01") }
    var recipient by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var memo by remember { mutableStateOf("") }
    val amountState = AmountState.newFromZec(amount, "", false, ExchangeRateState.OptedOut)
    val validAmount = (amountState as? AmountState.Valid)?.zatoshi?.value?.let { it > 0 } == true
    val validMemo = MemoState.new(memo) is MemoState.Correct
    if (donation) Text("Help maintain and improve ZEC Private.", style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = amount, onValueChange = { amount = it }, label = { Text("Amount") },
        suffix = { Text("ZEC") }, supportingText = { Text("Available: 0.125 ZEC") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = amount.isNotEmpty() && !validAmount, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    if (!donation) OutlinedTextField(
        value = recipient, onValueChange = { recipient = it }, label = { Text("Send to") },
        placeholder = { Text("Paste an address") }, trailingIcon = {
            IconButton(onClick = {}) { Icon(Icons.Outlined.QrCodeScanner, "Scan address") }
        }, modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = { expanded = !expanded }) {
        Icon(Icons.Outlined.ChatBubbleOutline, null)
        Spacer(Modifier.width(8.dp))
        Text(if (expanded) "Hide message" else "Add a message (optional)")
    }
    if (expanded) OutlinedTextField(
        value = memo, onValueChange = { memo = it }, label = { Text("Message") },
        supportingText = { Text("${memo.toByteArray(Charsets.UTF_8).size} / 512 bytes") },
        isError = !validMemo, minLines = 3, modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = {}, enabled = donation && validAmount && validMemo, modifier = Modifier.fillMaxWidth()) {
        Text(if (donation) "Review donation" else "Review transaction")
    }
    if (donation) TextButton(onClick = {}) {
        Text("Donation addresses")
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(18.dp))
    }
}
