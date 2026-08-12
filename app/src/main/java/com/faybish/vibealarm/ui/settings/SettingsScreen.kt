package com.faybish.vibealarm.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.R
import com.faybish.vibealarm.ui.components.LabeledRow
import com.faybish.vibealarm.ui.components.SwitchRow
import com.faybish.vibealarm.ui.update.UpdateDialogHost
import com.faybish.vibealarm.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(updateViewModel: UpdateViewModel, onBack: () -> Unit) {
    val settings = AppGraph.settings
    val scope = rememberCoroutineScope()
    val volumeKeysSnooze by settings.volumeKeysSnooze.collectAsStateWithLifecycle(initialValue = true)
    val forcePwm by settings.forcePwmFlow.collectAsStateWithLifecycle(initialValue = false)
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // The app also checks on every open; this is the "check right now" path,
            // and unlike the automatic one it always reports back.
            LabeledRow(
                title = stringResource(R.string.setting_check_update),
                value = updateViewModel.installedVersion
                    ?.let { stringResource(R.string.setting_version, it) }.orEmpty(),
                onClick = updateViewModel::checkNow,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SwitchRow(
                title = stringResource(R.string.setting_volume_keys_snooze),
                subtitle = stringResource(R.string.setting_volume_keys_snooze_hint),
                checked = volumeKeysSnooze,
                onCheckedChange = { scope.launch { settings.setVolumeKeysSnooze(it) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.settings_advanced),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SwitchRow(
                title = stringResource(R.string.setting_force_pwm),
                subtitle = stringResource(R.string.setting_force_pwm_hint),
                checked = forcePwm,
                onCheckedChange = { scope.launch { settings.setForcePwmEmulation(it) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            UpdateDialogHost(
                state = updateState,
                installedVersion = updateViewModel.installedVersion,
                onDownload = updateViewModel::download,
                onSkip = updateViewModel::skip,
                onDismiss = updateViewModel::dismiss,
                onOpenInstallSettings = updateViewModel::openInstallPermissionSettings,
            )

            Text(
                text = stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
