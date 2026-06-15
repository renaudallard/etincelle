// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import it.allard.etincelle.core.cast.CastUiState

/** Cast control for the top bar: shows a picker of discovered Chromecasts, or the connected one. */
@Composable
fun CastButton(state: CastUiState, onConnect: (String) -> Unit, onDisconnect: () -> Unit) {
    if (!state.available) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Icon(painterResource(R.drawable.ic_cast), contentDescription = "Caster", modifier = Modifier.size(40.dp))
        val device = state.connectedDeviceName
        if (device != null) {
            Spacer(Modifier.width(6.dp))
            Text(device)
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Fermer") } },
            title = { Text("Lire sur") },
            text = {
                Column {
                    // This phone — selecting it while casting transfers playback back here.
                    TextButton(onClick = { if (state.isCasting) onDisconnect(); open = false }) {
                        Text((if (!state.isCasting) "• " else "") + "Cet appareil")
                    }
                    state.devices.forEach { device ->
                        val selected = state.isCasting && state.connectedDeviceName == device.name
                        TextButton(onClick = { if (!selected) onConnect(device.routeId); open = false }) {
                            Text((if (selected) "• " else "") + device.name)
                        }
                    }
                }
            },
        )
    }
}

/** Shown in place of the local video while a stream is playing on a Chromecast. */
@Composable
fun CastingPlaceholder(title: String?, deviceName: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📺", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(12.dp))
            Text(title ?: "Lecture en cours", style = MaterialTheme.typography.titleMedium)
            Text("Lecture sur $deviceName", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
