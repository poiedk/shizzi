package dev.shizzi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.shizzi.ClientLeaseUi
import dev.shizzi.ui.theme.ShizziTheme

@Composable
fun ClientIpPrioritySection(
    clients: List<ClientLeaseUi>,
    desiredIps: Map<String, String>,
    priority: List<String>,
    onSave: (String, String) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    val orderedClients = clients.sortedWith(
        compareBy<ClientLeaseUi> { client ->
            priority.indexOf(client.mac.lowercase()).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }.thenBy { it.mac },
    )

    if (orderedClients.isEmpty()) {
        ClientIpEmptyState()
        return
    }

    Text(
        text = "Client IP priority",
        style = ShizziTheme.typography.subheading,
        color = ShizziTheme.colors.onSurface,
    )
    Text(
        text = "Desired IPs are saved per MAC. Automatic provisioning is not enabled yet.",
        style = ShizziTheme.typography.body,
        color = ShizziTheme.colors.onSurfaceMuted,
    )
    Spacer(Modifier.height(ShizziTheme.spacing.sm))

    orderedClients.forEach { client ->
        val mac = client.mac.lowercase()
        val savedAddress = desiredIps[mac].orEmpty()
        val priorityIndex = priority.indexOf(mac)
        var draftAddress by remember(mac, savedAddress) { mutableStateOf(savedAddress) }

        Column(modifier = Modifier.fillMaxWidth()) {
            val rank = if (priorityIndex >= 0) "#${priorityIndex + 1}  " else ""
            Text(
                text = "${rank}${client.mac}",
                style = ShizziTheme.typography.log,
                color = ShizziTheme.colors.onSurface,
            )
            Text(
                text = "Current ${client.address}",
                style = ShizziTheme.typography.caption,
                color = ShizziTheme.colors.onSurfaceMuted,
            )
            OutlinedTextField(
                value = draftAddress,
                onValueChange = { draftAddress = it },
                singleLine = true,
                label = { Text("Desired IP") },
                placeholder = { Text("172.16.0.10") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onSave(mac, draftAddress.trim()) }) { Text("Save") }
                TextButton(enabled = priorityIndex > 0, onClick = { onMove(mac, -1) }) { Text("↑") }
                TextButton(
                    enabled = priorityIndex >= 0 && priorityIndex < priority.lastIndex,
                    onClick = { onMove(mac, 1) },
                ) { Text("↓") }
                if (savedAddress.isNotBlank()) {
                    TextButton(onClick = { draftAddress = ""; onRemove(mac) }) { Text("Remove") }
                }
            }
            Spacer(Modifier.height(ShizziTheme.spacing.md))
        }
    }
}

@Composable
private fun ClientIpEmptyState() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Client IP priority",
            style = ShizziTheme.typography.subheading,
            color = ShizziTheme.colors.onSurface,
        )
        Text(
            text = "Start the hotspot to manage connected clients.",
            style = ShizziTheme.typography.body,
            color = ShizziTheme.colors.onSurfaceMuted,
        )
    }
}
