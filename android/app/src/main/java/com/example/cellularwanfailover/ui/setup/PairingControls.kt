package com.example.cellularwanfailover.ui.setup

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cellularwanfailover.auth.PairingInvite
import com.example.cellularwanfailover.auth.PairingState
import com.example.cellularwanfailover.ui.AppActions
import com.example.cellularwanfailover.ui.components.AppIcons
import com.example.cellularwanfailover.ui.theme.LocalStatusColors
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * "Scan QR code" and "Paste link" buttons with their dialogs. [stacked]: the scan button full
 * width with a text button under it (first run); otherwise side by side (Setup).
 */
@Composable
fun PairingButtons(actions: AppActions, stacked: Boolean, scanLabel: String = "Scan QR code") {
    val context = LocalContext.current
    var showPasteDialog by remember { mutableStateOf(false) }
    var invalidCodeMessage by remember { mutableStateOf<String?>(null) }

    fun openLink(text: String) {
        if (PairingInvite.parse(text) != null) {
            actions.openPairingLink(text.trim())
        } else {
            invalidCodeMessage = "This isn't a gateway pairing code. On the gateway, run 'wanctl pair' and scan its QR code."
        }
    }

    val scan = {
        scanPairingQr(context = context, onResult = { openLink(it) }, onError = { invalidCodeMessage = it })
    }

    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = scan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(AppIcons.Qr, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(scanLabel, modifier = Modifier.padding(start = 8.dp), fontSize = 15.sp)
            }
            TextButton(onClick = { showPasteDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Paste pairing link instead", fontSize = 15.sp)
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = scan, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Icon(AppIcons.Qr, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(scanLabel, modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = { showPasteDialog = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Paste link")
            }
        }
    }

    if (showPasteDialog) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Paste pairing link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste the link printed by 'wanctl pair' on the gateway:", fontSize = 14.sp)
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("wanfailover://pair?...") },
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPasteDialog = false
                        openLink(input)
                    },
                    enabled = input.isNotBlank()
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showPasteDialog = false }) { Text("Cancel") } }
        )
    }

    invalidCodeMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { invalidCodeMessage = null },
            title = { Text("Can't pair") },
            text = { Text(message, fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { invalidCodeMessage = null }) { Text("OK") } }
        )
    }
}

/** Pairing in progress, or its outcome when its screen was left. Nothing when idle. */
@Composable
fun PairingStatusRow(pairingState: PairingState, actions: AppActions) {
    val colors = LocalStatusColors.current
    when (pairingState) {
        is PairingState.Waiting -> StatusRow(
            text = "Pairing with ${pairingState.gatewayName} on ${pairingState.ssid}: waiting for the gateway…",
            palette = colors.active,
            action = "Cancel" to actions.cancelPairing,
            showProgress = true
        )
        is PairingState.Failed -> StatusRow(
            text = "Pairing with ${pairingState.gatewayName} failed: ${pairingState.reason}",
            palette = colors.attention,
            action = "Dismiss" to actions.acknowledgePairingResult
        )
        is PairingState.Paired -> StatusRow(
            text = "Paired with ${pairingState.gatewayName} on ${pairingState.ssid}.",
            palette = colors.good,
            action = "OK" to actions.acknowledgePairingResult
        )
        PairingState.Idle -> Unit
    }
}

@Composable
private fun StatusRow(
    text: String,
    palette: com.example.cellularwanfailover.ui.theme.StatusPalette,
    action: Pair<String, () -> Unit>,
    showProgress: Boolean = false
) {
    Surface(shape = RoundedCornerShape(12.dp), color = palette.container, contentColor = palette.content) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showProgress) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = palette.accent)
            Text(text = text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(onClick = action.second) { Text(action.first, color = palette.content) }
        }
    }
}

/** Google Code Scanner: Play services shows the camera UI, so the app needs no camera permission. */
private fun scanPairingQr(context: Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
    try {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (raw.isNullOrBlank()) onError("The scanned QR code is empty.") else onResult(raw)
            }
            .addOnFailureListener { e -> onError("Couldn't scan: ${e.message ?: "scanner unavailable"}") }
        // Closing the scanner (back) is a cancel: nothing to report
    } catch (e: Exception) {
        onError("Google Code Scanner is unavailable: ${e.message}. Use 'Paste link' instead.")
    }
}
