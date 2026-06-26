package com.family.bankapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.family.bankapp.update.AppUpdateInstaller
import com.family.bankapp.ui.viewmodel.AppUpdateUiState
import com.family.bankapp.ui.viewmodel.AppUpdateViewModel

@Composable
fun AppUpdateSettingsCard(vm: AppUpdateViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("App updates", fontWeight = FontWeight.Bold)
            Text(
                "Installed: Family Bank ${vm.currentVersionName}",
                style = MaterialTheme.typography.bodySmall
            )

            when (val s = state) {
                is AppUpdateUiState.Checking -> {
                    Text(
                        "Checking for updates…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is AppUpdateUiState.Available -> {
                    Text(
                        "Update available: version ${s.manifest.versionName}",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    s.manifest.notes?.let { notes ->
                        Text(notes, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = vm::updateNow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Update now")
                    }
                }

                is AppUpdateUiState.Downloading -> {
                    val progress = s.progress
                    val percent = progress.percent
                    Text(
                        if (percent != null) "Downloading update… $percent%" else "Downloading update…",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        progress.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (percent != null) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is AppUpdateUiState.ReadyToInstall -> {
                    Text(
                        "Ready to install version ${s.manifest.versionName}",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "The Android installer should open automatically. If it does not, tap Open installer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = vm::updateNow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open installer")
                    }
                }

                is AppUpdateUiState.UpToDate -> {
                    Text(
                        "You're on the latest version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                is AppUpdateUiState.Error -> {
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (!AppUpdateInstaller.canInstallPackages(context)) {
                        OutlinedButton(
                            onClick = { AppUpdateInstaller.openInstallPermissionSettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Allow installs from this app")
                        }
                    }
                }

                else -> Unit
            }

            val showCheckButton = state !is AppUpdateUiState.Checking &&
                state !is AppUpdateUiState.Downloading &&
                state !is AppUpdateUiState.Available &&
                state !is AppUpdateUiState.ReadyToInstall

            if (showCheckButton) {
                OutlinedButton(
                    onClick = vm::checkForUpdate,
                    enabled = state !is AppUpdateUiState.Checking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check for updates")
                }
            }

            Text(
                "Publish new builds to GitHub Pages (docs/FamilyBank.apk) after assembleDebug.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
