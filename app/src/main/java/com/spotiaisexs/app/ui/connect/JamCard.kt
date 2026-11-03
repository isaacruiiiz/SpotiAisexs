package com.spotiaisexs.app.ui.connect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * "Jam" card (Ajustes → Web y Jam), in the app's "Cristal, color de portada"
 * style: tinted glass card, accent pills, and the QR on a white tile. Friends
 * open the link or scan the QR (no app, no account), see the queue and add
 * songs with ⋯ or a swipe.
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
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(26.dp)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Jam", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (open) "Tus amigos ven la cola y añaden canciones" else "Escuchad juntos desde un enlace, sin app ni cuenta",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (open) {
                    Surface(shape = CircleShape, color = accent.copy(alpha = 0.18f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Box(Modifier.size(7.dp).background(accent, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text("En directo", style = MaterialTheme.typography.labelMedium, color = accent)
                        }
                    }
                }
            }

            if (!open) {
                OutlinedTextField(
                    value = hostName,
                    onValueChange = { hostName = it.take(30) },
                    label = { Text("Tu nombre en la Jam") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, focusedLabelColor = accent, cursorColor = accent),
                    modifier = Modifier.fillMaxWidth(),
                )
                AccentButton(text = "Empezar Jam", busy = busy, onClick = { viewModel.startJam(hostName) })
            } else if (link == null) {
                Text(
                    "La Jam está abierta, pero falta la conexión con la web para generar el enlace. Vuelve a conectar arriba.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = viewModel::endJam, enabled = !busy) { Text("Terminar Jam", color = accent) }
            } else {
                // The QR: black modules on a white tile so any camera reads it.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .widthIn(max = 260.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color.White, RoundedCornerShape(22.dp))
                            .padding(18.dp),
                    ) {
                        JamQr(link, Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                }
                Text(
                    "Que lo escaneen con la cámara o mándales el enlace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Únete a mi Jam y añade canciones a la cola 🎶 $link")
                            }
                            context.startActivity(Intent.createChooser(send, "Invitar a la Jam"))
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Compartir enlace", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { copyLink(context, link) },
                        shape = CircleShape,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar enlace", modifier = Modifier.size(18.dp))
                    }
                }

                val now = System.currentTimeMillis()
                val guests = jam?.guests.orEmpty().values
                    .filter { now - it.lastSeen < 90_000 }
                    .map { it.name }
                    .distinct()
                Text(
                    if (guests.isEmpty()) "Aún no se ha unido nadie" else "En la Jam: ${guests.joinToString(", ")}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = viewModel::endJam, enabled = !busy) {
                    Text("Terminar Jam", color = accent, fontWeight = FontWeight.SemiBold)
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AccentButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/** Draws the QR modules straight onto a Canvas: crisp at any size, no bitmap to fail. */
@Composable
fun JamQr(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { jamQrMatrix(text) }
    if (matrix == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No se pudo crear el QR", color = Color.Black, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Canvas(modifier.semantics { contentDescription = "Código QR para unirse a la Jam" }) {
        val cells = matrix.width
        val cell = size.minDimension / cells
        for (y in 0 until cells) {
            for (x in 0 until cells) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        // A hair over one cell so neighbouring modules never show seams.
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

/** Module matrix with no quiet zone (the white tile provides it). Also used for Android Auto's QR. */
fun jamQrMatrix(text: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
}.onFailure { android.util.Log.w("JamCard", "QR encode failed", it) }.getOrNull()

private fun copyLink(context: Context, link: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Jam", link))
    Toast.makeText(context, "Enlace copiado", Toast.LENGTH_SHORT).show()
}
