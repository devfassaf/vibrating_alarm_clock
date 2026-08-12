package com.faybish.vibealarm.ui.reliability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faybish.vibealarm.R
import com.faybish.vibealarm.alarm.CheckId
import com.faybish.vibealarm.alarm.CheckResult
import com.faybish.vibealarm.alarm.CheckStatus
import com.faybish.vibealarm.ui.format.formatInstantTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * One screen that answers "will my alarm actually go off?" — every platform and
 * vendor requirement, its current state, a button that opens the page which fixes
 * it, and a reboot test that proves the before-first-unlock path works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReliabilityScreen(viewModel: ReliabilityViewModel, onBack: () -> Unit) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var verdict by remember { mutableStateOf(RebootTestVerdict.NOT_RUN) }

    val noSettingsMessage = stringResource(R.string.reliability_no_settings_page)

    LaunchedEffect(Unit) {
        viewModel.refresh()
        verdict = viewModel.rebootTestVerdict()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_reliability)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
                start = 12.dp,
                end = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "${viewModel.deviceName} · ${viewModel.androidVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(results, key = { it.id.name }) { result ->
                CheckCard(
                    result = result,
                    onFix = {
                        if (!viewModel.openFix(result.id)) {
                            scope.launch { snackbar.showSnackbar(noSettingsMessage) }
                        }
                    },
                )
            }

            item { RebootTestCard(viewModel, verdict) { verdict = it } }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.reliability_log_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            items(log, key = { it.id }) { entry ->
                Column {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = logTimeFormatter.format(
                                Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault()),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = entry.event,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (entry.detail.isNotBlank()) {
                        Text(
                            text = entry.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun CheckCard(result: CheckResult, onFix: () -> Unit) {
    val (icon, tint) = when (result.status) {
        CheckStatus.OK -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        CheckStatus.ACTION_NEEDED -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
        CheckStatus.INFO -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
        CheckStatus.MANUAL -> Icons.Filled.Info to MaterialTheme.colorScheme.tertiary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(text = checkTitle(result.id), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = checkDescription(result.id),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result.fixable && result.status != CheckStatus.OK) {
                    TextButton(onClick = onFix, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.action_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun RebootTestCard(
    viewModel: ReliabilityViewModel,
    verdict: RebootTestVerdict,
    onVerdict: (RebootTestVerdict) -> Unit,
) {
    var scheduledFor by remember { mutableStateOf<Instant?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null)
                Text(
                    text = stringResource(R.string.reboot_test_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                text = stringResource(R.string.reboot_test_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            val verdictText = when (verdict) {
                RebootTestVerdict.NOT_RUN -> null
                RebootTestVerdict.WAITING -> stringResource(R.string.reboot_test_waiting)
                RebootTestVerdict.BOOTED_NOT_FIRED -> stringResource(R.string.reboot_test_failed)
                RebootTestVerdict.PASSED -> stringResource(R.string.reboot_test_passed)
            }
            if (verdictText != null) {
                Text(
                    text = verdictText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (verdict) {
                        RebootTestVerdict.PASSED -> MaterialTheme.colorScheme.primary
                        RebootTestVerdict.BOOTED_NOT_FIRED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            scheduledFor?.let {
                Text(
                    text = stringResource(R.string.reboot_test_scheduled, formatInstantTime(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            OutlinedButton(
                onClick = {
                    viewModel.scheduleRebootTest { at ->
                        scheduledFor = at
                        onVerdict(RebootTestVerdict.WAITING)
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.reboot_test_start)) }
        }
    }
}

@Composable
private fun checkTitle(id: CheckId): String = stringResource(
    when (id) {
        CheckId.EXACT_ALARMS -> R.string.check_exact_alarms
        CheckId.NOTIFICATIONS -> R.string.check_notifications
        CheckId.FULL_SCREEN_INTENT -> R.string.check_full_screen
        CheckId.BATTERY_OPTIMIZATION -> R.string.check_battery
        CheckId.AMPLITUDE_CONTROL -> R.string.check_amplitude
        CheckId.SYSTEM_VIBRATION_STRENGTH -> R.string.check_system_vibration
        CheckId.OEM_BACKGROUND_LIMITS -> R.string.check_oem
    },
)

@Composable
private fun checkDescription(id: CheckId): String = stringResource(
    when (id) {
        CheckId.EXACT_ALARMS -> R.string.check_exact_alarms_description
        CheckId.NOTIFICATIONS -> R.string.check_notifications_description
        CheckId.FULL_SCREEN_INTENT -> R.string.check_full_screen_description
        CheckId.BATTERY_OPTIMIZATION -> R.string.check_battery_description
        CheckId.AMPLITUDE_CONTROL -> R.string.check_amplitude_description
        CheckId.SYSTEM_VIBRATION_STRENGTH -> R.string.check_system_vibration_description
        CheckId.OEM_BACKGROUND_LIMITS -> R.string.check_oem_description
    },
)

private val logTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")
