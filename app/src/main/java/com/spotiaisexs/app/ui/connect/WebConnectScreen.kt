package com.spotiaisexs.app.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotiaisexs.app.data.connect.ConnectStatus
import com.spotiaisexs.app.ui.common.ExpressiveHeader
import com.spotiaisexs.app.ui.common.adaptiveContentWidth
import com.spotiaisexs.app.ui.player.LocalMiniPlayerScrollClearance

/**
 * Settings → "Conectar con la web": links this phone to the SpotiAisexs web
 * app through the user's Firebase project (setup in docs/WEB_SYNC.md).
 */
@Composable
fun WebConnectScreen(
    onBack: () -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val connected = status is ConnectStatus.Connected

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 720.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            ExpressiveHeader(
                title = "Conectar con la web",
                subtitle = "Controla y escucha desde el navegador",
                onBack = onBack,
            )
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 96.dp + LocalMiniPlayerScrollClearance.current),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { StatusCard(status, onDisconnect = viewModel::disconnect) }

                if (!connected) {
                    item {
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Proyecto de Firebase", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Copia el bloque firebaseConfig de la consola de Firebase (o de web/config.js) y pégalo aquí.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = { clipboard.getText()?.text?.let(viewModel::pasteFirebaseConfig) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Pegar configuración") }
                                OutlinedTextField(
                                    value = form.apiKey,
                                    onValueChange = viewModel::onApiKey,
                                    label = { Text("API key") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = form.databaseUrl,
                                    onValueChange = viewModel::onDatabaseUrl,
                                    label = { Text("URL de Realtime Database") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    item {
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Tu cuenta", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "La misma que usas para entrar en la web. La contraseña no se guarda en el teléfono.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = form.email,
                                    onValueChange = viewModel::onEmail,
                                    label = { Text("Correo") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = form.password,
                                    onValueChange = viewModel::onPassword,
                                    label = { Text("Contraseña") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                                Button(
                                    onClick = viewModel::connect,
                                    enabled = status !is ConnectStatus.Connecting &&
                                        form.apiKey.isNotBlank() && form.databaseUrl.isNotBlank() &&
                                        form.email.isNotBlank() && form.password.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                ) {
                                    if (status is ConnectStatus.Connecting) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text("Conectar", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: ConnectStatus, onDisconnect: () -> Unit) {
    val (title, detail) = when (status) {
        ConnectStatus.Off -> "Sin conectar" to "Rellena los datos de abajo para enlazar el teléfono con la web."
        ConnectStatus.Connecting -> "Conectando…" to "Comprobando la cuenta con Firebase."
        is ConnectStatus.Connected -> "Conectado" to "${status.email} · lo que suene aquí se verá en la web al momento, y al revés."
        is ConnectStatus.Error -> "Error" to status.message
    }
    val dot = when (status) {
        is ConnectStatus.Connected -> MaterialTheme.colorScheme.primary
        is ConnectStatus.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = dot, modifier = Modifier.size(12.dp)) {}
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status is ConnectStatus.Connected || status is ConnectStatus.Error) {
                TextButton(onClick = onDisconnect) { Text("Desconectar") }
            }
        }
    }
}
