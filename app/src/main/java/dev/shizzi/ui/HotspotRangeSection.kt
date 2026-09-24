package dev.shizzi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shizzi.HotspotRange
import dev.shizzi.ui.theme.ShizziTheme

private val RangeCheckSize = 20.dp

fun hotspotRangeLabel(range: HotspotRange): String = when (range) {
    HotspotRange.DEFAULT_192 -> "192.168.x.0/24"
    HotspotRange.PRIVATE_172 -> "172.16–31.x.0/24"
    HotspotRange.PRIVATE_10 -> "10.x.x.0/24"
    HotspotRange.CUSTOM -> "Manual /24"
}

private fun hotspotRangeDescription(range: HotspotRange): String = when (range) {
    HotspotRange.DEFAULT_192 -> "Use Android's default 192.168/16 tethering pool"
    HotspotRange.PRIVATE_172 -> "Force Android to pick a /24 from 172.16.0.0/12"
    HotspotRange.PRIVATE_10 -> "Force Android to pick a /24 from 10.0.0.0/8"
    HotspotRange.CUSTOM -> "Force one exact private /24, e.g. 172.16.0.0/24"
}

@Composable
fun HotspotRangeSection(
    selected: HotspotRange,
    customSubnet: String,
    onSelect: (HotspotRange) -> Unit,
    onSetCustomSubnet: (String) -> Unit,
) {
    var isOpen by remember { mutableStateOf(false) }
    var draftSubnet by remember(customSubnet) { mutableStateOf(customSubnet) }

    SettingsChoice(
        label = SettingsText(
            title = "Hotspot address range",
            subtitle = "Applied the next time the session starts",
        ),
        value = if (selected == HotspotRange.CUSTOM) customSubnet else hotspotRangeLabel(selected),
        onClick = { isOpen = true },
    )

    if (!isOpen) return

    ThemedBottomSheet(onDismiss = { isOpen = false }) {
        Text(
            text = "Hotspot address range",
            style = ShizziTheme.typography.heading,
            color = ShizziTheme.colors.onSurface,
        )

        HotspotRange.entries.forEach { range ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSelect(range)
                        if (range == HotspotRange.CUSTOM) {
                            draftSubnet = customSubnet
                        } else {
                            isOpen = false
                        }
                    }
                    .padding(vertical = ShizziTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = hotspotRangeLabel(range),
                        style = ShizziTheme.typography.subheading,
                        color = ShizziTheme.colors.onSurface,
                    )
                    Text(
                        text = hotspotRangeDescription(range),
                        style = ShizziTheme.typography.body,
                        color = ShizziTheme.colors.onSurfaceMuted,
                    )
                }

                if (range == selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = ShizziTheme.colors.primary,
                        modifier = Modifier.size(RangeCheckSize),
                    )
                }
            }
        }

        if (selected == HotspotRange.CUSTOM) {
            Spacer(Modifier.height(ShizziTheme.spacing.md))

            OutlinedTextField(
                value = draftSubnet,
                onValueChange = { draftSubnet = it },
                singleLine = true,
                label = { Text("Subnet") },
                supportingText = { Text("Private IPv4 /24, e.g. 172.16.0.0/24") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(ShizziTheme.spacing.md))

            Button(
                onClick = {
                    onSetCustomSubnet(draftSubnet.trim())
                    isOpen = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Apply manual subnet")
            }
        }

        Spacer(Modifier.height(ShizziTheme.spacing.lg))
    }
}
