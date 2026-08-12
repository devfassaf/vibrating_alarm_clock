package com.faybish.vibealarm.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.R
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
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val noBrowserMessage = stringResource(R.string.setting_no_browser)
    val noShareMessage = stringResource(R.string.setting_no_share_target)
    val shareSubject = stringResource(R.string.setting_share_subject)

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
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Buttons, not tappable rows: the previous version of this looked like a
            // line of information and people did not realise it could be pressed.
            Spacer(Modifier.height(8.dp))

            ActionButton(
                text = stringResource(R.string.setting_check_update),
                icon = Icons.Filled.Refresh,
                onClick = updateViewModel::checkNow,
            )

            ActionButton(
                text = stringResource(R.string.setting_open_site),
                icon = Icons.Filled.Public,
                onClick = {
                    if (!SettingsActions.openSite(context)) {
                        scope.launch { snackbar.showSnackbar(noBrowserMessage) }
                    }
                },
            )

            ActionButton(
                text = stringResource(R.string.setting_share_app),
                icon = Icons.Filled.Share,
                onClick = {
                    val shared = SettingsActions.shareApp(
                        context = context,
                        version = updateViewModel.installedVersion,
                        subject = shareSubject,
                    )
                    if (!shared) scope.launch { snackbar.showSnackbar(noShareMessage) }
                },
            )

            updateViewModel.installedVersion?.let {
                Text(
                    text = stringResource(R.string.setting_version, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }

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

/** Full-width, icon-led: unmistakably something you press. */
@Composable
private fun ActionButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(text = text, modifier = Modifier.padding(start = 12.dp))
    }
}
