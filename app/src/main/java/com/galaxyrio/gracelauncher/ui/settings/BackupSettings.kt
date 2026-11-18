package com.galaxyrio.gracelauncher.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun BackupSettings(onBack: () -> Unit, actions: LauncherActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                actions.exportBackup(uri)
                android.widget.Toast.makeText(context, R.string.backup_export_success, android.widget.Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.widget.Toast.makeText(context,
                    context.getString(R.string.backup_operation_failed, error.localizedMessage ?: error.javaClass.simpleName),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingRestore = uri
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_restore_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        try {
                            actions.restoreBackup(uri)
                            android.widget.Toast.makeText(context, R.string.backup_restore_success, android.widget.Toast.LENGTH_LONG).show()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.widget.Toast.makeText(context,
                                context.getString(R.string.backup_operation_failed, error.localizedMessage ?: error.javaClass.simpleName),
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(stringResource(R.string.backup_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    SettingsScaffold(stringResource(R.string.backup_title), "settings_backup", onBack) { padding ->
        SettingsList(padding) {
            item {
                Text(
                    stringResource(R.string.backup_instructions),
                    Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SettingsActionItem(
                    stringResource(R.string.backup_create),
                    stringResource(R.string.backup_create_summary),
                    0, 2, "settings_backup_create",
                    leading = { LauncherIcon(LauncherSymbol.Settings) },
                ) { createBackup.launch("GraceLauncher-backup-${LocalDate.now()}.json") }
            }
            item {
                SettingsActionItem(
                    stringResource(R.string.backup_restore),
                    stringResource(R.string.backup_restore_summary),
                    1, 2, "settings_backup_restore",
                    leading = { LauncherIcon(LauncherSymbol.Folder) },
                ) { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            }
        }
    }
}
