package com.spotiaisexs.app.ui.connect

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * "Jam" card (Ajustes → Web y Jam): opens a shared session. Friends open the
 * link or QR (no app, no account), see the queue, search and add songs with
 * the ⋯ menu or a swipe — like Spotify's queue.
 */
@Composable
fun JamCard(viewModel: ConnectViewModel, defaultHostName: String) {
    val jam by viewModel.jam.collectAsStateWithLifecycle()
    val busy by viewModel.jamBusy.collectAsStateWithLifecycle()
    val error by viewModel.jamError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hostName by rememberSaveable { mutableStateOf(defaultHostName) }
    val open = jam?.open == true
    val link = viewModel.jamLink(jam)

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Jam", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (open) "Tus amigos pueden añadir canciones a tu cola"
                        else "Escuchad juntos: tus amigos añaden canciones desde un enlace, aunque usen Spotify",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (open) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Text(
                            "EN DIRECTO",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            if (!open || link == null) {
                OutlinedTextField(
                    value = hostName,
                    onValueChange = { hostName = it.take(30) },
                    label = { Text("Tu nombre en la Jam") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.startJam(hostName) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("Empezar Jam", fontWeight = FontWeight.Bold)
                }
            } else {
                val qr = remember(link) { qrBitmap(link, 720) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Código QR para unirse a la Jam",
                            modifier = Modifier.size(220.dp),
                        )
                    }
                }
                Text(
                    "Que lo escaneen con la cámara, o mándales el enlace. Verán la cola y podrán añadir canciones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Únete a mi Jam y añade canciones a la cola 🎶 $link")
                        }
                        context.startActivity(Intent.createChooser(send, "Invitar a la Jam"))
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text("Compartir enlace", fontWeight = FontWeight.Bold) }

                val now = System.currentTimeMillis()
                val guests = jam?.guests.orEmpty().values
                    .filter { now - it.lastSeen < 90_000 }
                    .map { it.name }
                    .distinct()
                Text(
                    if (guests.isEmpty()) "Aún no se ha unido nadie"
                    else "En la Jam: ${guests.joinToString(", ")}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = viewModel::endJam,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Terminar Jam") }
            }
            error?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) AndroidColor.BLACK else AndroidColor.WHITE
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()
