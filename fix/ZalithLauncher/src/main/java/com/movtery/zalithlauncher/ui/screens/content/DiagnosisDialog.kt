/*
 * Maou Launcher - dialog penjelasan masalah game (bahasa Indonesia).
 */
package com.movtery.zalithlauncher.ui.screens.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.game.diagnose.DiagnosisStore
import com.movtery.zalithlauncher.game.diagnose.Finding
import com.movtery.zalithlauncher.game.diagnose.Severity

@Composable
fun DiagnosisDialog(findings: List<Finding>, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apa yang terjadi pada game") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                findings.take(12).forEach { f ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = (when (f.severity) { Severity.ERROR -> "⛔ "; Severity.WARN -> "⚠️ "; Severity.INFO -> "ℹ️ " }) + f.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (f.detail.isNotBlank()) Text(f.detail, style = MaterialTheme.typography.bodySmall)
                        Text("Saran: ${f.advice}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Mengerti") } },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(DiagnosisStore.asText(findings))) }) { Text("Salin") }
        }
    )
}
