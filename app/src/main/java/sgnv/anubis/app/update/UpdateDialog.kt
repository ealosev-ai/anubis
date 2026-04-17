package sgnv.anubis.app.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Доступно обновление: v${info.latestVersion}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Текущая версия: v${info.currentVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (info.apkSha256 != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "SHA-256: ${info.apkSha256.take(16)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                } else if (info.apkUrl != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ В релизе не указан SHA-256 — целостность APK проверить нечем.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (info.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        info.releaseNotes,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                status?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working,
                onClick = {
                    if (info.apkSha256 != null && info.apkUrl != null) {
                        working = true
                        status = "Скачиваем и проверяем SHA-256…"
                        coroutineScope.launch {
                            when (val r = UpdateInstaller.downloadAndPrepare(context, info)) {
                                is UpdateInstaller.Result.Ready -> {
                                    context.startActivity(r.installIntent)
                                    onDismiss()
                                }
                                is UpdateInstaller.Result.HashMismatch -> {
                                    status = "SHA-256 не совпал. Ожидали ${r.expected.take(16)}… " +
                                        "получили ${r.actual.take(16)}… Установка отменена."
                                    working = false
                                }
                                is UpdateInstaller.Result.Error -> {
                                    status = r.message
                                    working = false
                                }
                            }
                        }
                    } else {
                        val url = info.apkUrl ?: info.releaseUrl
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        onDismiss()
                    }
                },
            ) {
                Text(
                    when {
                        working -> "…"
                        info.apkSha256 != null -> "Проверить и установить"
                        info.apkUrl != null -> "Скачать APK (без проверки)"
                        else -> "Открыть релиз"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip, enabled = !working) { Text("Пропустить") }
        },
    )
}
