package com.spotiaisexs.app.ui.connect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotiaisexs.app.data.connect.ConnectStatus

/**
 * Quick access to the Jam from the mini player: the same card as in
 * Ajustes → Web y Jam, in a bottom sheet, so starting a Jam or showing the QR
 * is one tap away from anywhere in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamQuickSheet(onDismiss: () -> Unit) {
    val viewModel: ConnectViewModel = hiltViewModel()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            when (val s = status) {
                is ConnectStatus.Connected -> JamCard(
                    viewModel = viewModel,
                    defaultHostName = s.email.substringBefore('@'),
                )
                ConnectStatus.Connecting -> Text(
                    "Conectando con la web…",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                else -> Column(Modifier.padding(vertical = 12.dp)) {
                    Text("Jam", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Para empezar una Jam, conecta antes el móvil con la web en Ajustes → Web y Jam. Solo hay que hacerlo una vez.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
