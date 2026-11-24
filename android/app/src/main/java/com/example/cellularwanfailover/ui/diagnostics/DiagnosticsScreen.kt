package com.example.cellularwanfailover.ui.diagnostics

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cellularwanfailover.model.CellularState
import com.example.cellularwanfailover.model.FailoverState
import com.example.cellularwanfailover.model.LogEntry
import com.example.cellularwanfailover.ui.AppActions
import com.example.cellularwanfailover.ui.AppState
import com.example.cellularwanfailover.ui.LogCategory
import com.example.cellularwanfailover.ui.components.AppIcons
import com.example.cellularwanfailover.ui.components.CardDivider
import com.example.cellularwanfailover.ui.components.DetailRow
import com.example.cellularwanfailover.ui.components.HelpText
import com.example.cellularwanfailover.ui.components.ScreenTitle
import com.example.cellularwanfailover.ui.components.SectionCard
import com.example.cellularwanfailover.ui.components.SectionHeader
import com.example.cellularwanfailover.ui.logCategory
import com.example.cellularwanfailover.ui.status.StopFailoverDialog
import com.example.cellularwanfailover.ui.theme.LocalStatusColors
import com.example.cellularwanfailover.ui.theme.StatusPalette

/** Log filter chips: null category = all events. */
private enum class LogFilter(val category: LogCategory?) {
    ALL(null), ERRORS(null), FAILOVER(LogCategory.FAILOVER), CONTROL_API(LogCategory.CONTROL_API),
    PAIRING(LogCategory.PAIRING), POWER(LogCategory.POWER);

    fun matches(entry: LogEntry): Boolean = when (this) {
        ALL -> true
        ERRORS -> entry.isError
        else -> logCategory(entry.tag) == category
    }
}

private const val LOG_PAGE = 50

@Composable
fun DiagnosticsScreen(state: AppState, actions: AppActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var shownCount by rememberSaveable { mutableIntStateOf(LOG_PAGE) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // Newest first
    val filtered = remember(state.logs, filter) { state.logs.asReversed().filter(filter::matches) }
    val shown = filtered.take(shownCount)
    val errorCount = remember(state.logs) { state.logs.count { it.isError } }

    fun copy(label: String, text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("Diagnostics") }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("Connection details")
                ConnectionDetails(state, onCopyKey = { copy("Public key", state.publicKey) })

                SectionHeader("Manual controls")
                SectionCard {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Stop failover relay", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            HelpText(
                                if (state.failoverState == FailoverState.ACTIVE) "Releases mobile data now"
                                else "Available while failover is active"
                            )
                        }
                        Button(
                            onClick = { confirmStop = true },
                            enabled = state.failoverState == FailoverState.ACTIVE,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                        ) { Text("Stop") }
                    }
                }

                SectionHeader("Event log · ${filtered.size}") {
                    IconButton(onClick = { copy("Event log", filtered.asReversed().joinToString("\n") { "${it.timestamp} [${it.tag}] ${it.message}" }) }, enabled = filtered.isNotEmpty()) {
                        Icon(AppIcons.Copy, contentDescription = "Copy log", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { confirmClear = true }, enabled = state.logs.isNotEmpty()) {
                        Icon(AppIcons.Trash, contentDescription = "Clear log", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f; shownCount = LOG_PAGE },
                        label = {
                            Text(
                                when (f) {
                                    LogFilter.ALL -> "All"
                                    LogFilter.ERRORS -> "Errors · $errorCount"
                                    else -> f.category!!.label
                                }
                            )
                        }
                    )
                }
            }
        }

        if (shown.isEmpty()) {
            item {
                LogCardPart(shape = RoundedCornerShape(16.dp)) {
                    HelpText(
                        if (state.logs.isEmpty()) "No events recorded yet" else "No matching events",
                        modifier = Modifier.fillMaxWidth().padding(20.dp)
                    )
                }
            }
        } else {
            itemsIndexed(shown, key = { _, entry -> entry.id }) { index, entry ->
                val first = index == 0
                val last = index == shown.lastIndex && shown.size >= filtered.size
                LogCardPart(
                    shape = RoundedCornerShape(
                        topStart = if (first) 16.dp else 0.dp, topEnd = if (first) 16.dp else 0.dp,
                        bottomStart = if (last) 16.dp else 0.dp, bottomEnd = if (last) 16.dp else 0.dp
                    )
                ) {
                    if (!first) CardDivider(inset = 0.dp)
                    LogRow(entry)
                }
            }
            if (shown.size < filtered.size) {
                item {
                    LogCardPart(shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)) {
                        CardDivider(inset = 0.dp)
                        TextButton(onClick = { shownCount += LOG_PAGE }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Show older events (${filtered.size - shown.size})")
                        }
                    }
                }
            }
        }
    }

    if (confirmStop) StopFailoverDialog(onConfirm = { confirmStop = false; actions.stopFailover() }, onDismiss = { confirmStop = false })
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the event log?") },
            text = { Text("All ${state.logs.size} events are deleted.", fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { confirmClear = false; actions.clearLogs() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ConnectionDetails(state: AppState, onCopyKey: () -> Unit) {
    val active = state.failoverState == FailoverState.ACTIVE
    SectionCard(modifier = Modifier.padding(vertical = 0.dp)) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            DetailRow("Wi-Fi address", state.wifiIp ?: "Not connected", valueStyleMono = state.wifiIp != null)
            CardDivider()
            DetailRow("Trusted network", if (state.isNetworkTrusted) "Yes · ${state.currentSsid ?: "paired"}" else "No")
            CardDivider()
            DetailRow(
                "Cellular",
                when (state.cellularState) {
                    CellularState.DISCONNECTED -> "Standby"
                    CellularState.CONNECTING -> "Connecting"
                    CellularState.CONNECTED -> "Connected"
                }
            )
            CardDivider()
            DetailRow(
                "WireGuard relay",
                when {
                    active -> "Active · UDP 51820"
                    !state.isNetworkTrusted -> "Halted · UDP 51820"
                    else -> "Standby · UDP 51820"
                }
            )
            CardDivider()
            DetailRow("Control API", if (state.isNetworkTrusted) "Listening · TCP 8989" else "Halted · TCP 8989")
            CardDivider()
            DetailRow("Standby", if (state.standbyHot) "Hot (always-on tunnel)" else "Cold (on demand)")
            CardDivider()
            DetailRow(
                "Phone public key",
                state.publicKey.take(8) + "…" + state.publicKey.takeLast(5),
                valueStyleMono = true,
                trailing = {
                    IconButton(onClick = onCopyKey) {
                        Icon(AppIcons.Copy, contentDescription = "Copy public key", modifier = Modifier.size(18.dp))
                    }
                }
            )
        }
    }
}

/** One slice of the log card: the log is a lazy list, so each row draws its part of the card. */
@Composable
private fun LogCardPart(shape: Shape, content: @Composable () -> Unit) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column { content() }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val colors = LocalStatusColors.current
    val category = logCategory(entry.tag)
    val palette: StatusPalette = when {
        entry.isError -> colors.attention
        category == LogCategory.FAILOVER -> colors.active
        category == LogCategory.PAIRING -> colors.good
        category == LogCategory.POWER -> colors.paused
        else -> colors.neutral
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.timestamp, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(6.dp), color = palette.container, contentColor = palette.content) {
                Text(
                    if (category == LogCategory.OTHER) entry.tag else category.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
            if (entry.isError) {
                Icon(AppIcons.Warning, contentDescription = "Error", modifier = Modifier.size(14.dp), tint = colors.attention.accent)
            }
        }
        Text(
            entry.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = if (entry.isError) colors.attention.accent else MaterialTheme.colorScheme.onSurface
        )
    }
}
