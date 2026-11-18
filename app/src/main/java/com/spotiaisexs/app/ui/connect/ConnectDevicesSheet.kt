package com.spotiaisexs.app.ui.connect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotiaisexs.app.data.connect.ConnectStatus
import com.spotiaisexs.app.ui.common.DialHaptics

/**
 * "¿Dónde suena?": this phone and the account's other devices that are online
 * (the web in a browser). Tapping one passes the music there at the same
 * second; tapping this phone brings it back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectDevicesSheet(onDismiss: () -> Unit) {
    val viewModel: ConnectViewModel = hiltViewModel()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val remote by viewModel.remotePlayback.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text("¿Dónde suena?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(8.dp))
            if (status !is ConnectStatus.Connected) {
                Text(
                    "Enlaza el teléfono con la web en Ajustes, Web y Jam, y podrás pasar la música al navegador y controlarla desde allí.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                val remoteId = remote?.activeDevice
                DeviceRow(
                    icon = Icons.Filled.Smartphone,
                    name = "Este teléfono",
                    active = remoteId == null,
                    onClick = {
                        if (remoteId != null) viewModel.playHere()
                        onDismiss()
                    },
                )
                devices.forEach { device ->
                    DeviceRow(
                        icon = if (device.type == "phone") Icons.Filled.Smartphone else Icons.Filled.Computer,
                        name = device.name,
                        active = device.id == remoteId,
                        onClick = {
                            if (device.id != remoteId) viewModel.transferTo(device.id)
                            onDismiss()
                        },
                    )
                }
                if (devices.isEmpty()) {
                    Text(
                        "Abre la web en un navegador y entra con tu cuenta para verla aquí.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, active: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) {
                DialHaptics.click(view)
                onClick()
            }
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (active) "Sonando aquí" else "Toca para escuchar aquí",
                style = MaterialTheme.typography.bodySmall,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (active) Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
