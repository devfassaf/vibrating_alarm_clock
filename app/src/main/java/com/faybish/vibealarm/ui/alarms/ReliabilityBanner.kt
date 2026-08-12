package com.faybish.vibealarm.ui.alarms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.R
import com.faybish.vibealarm.alarm.CheckStatus
import com.faybish.vibealarm.alarm.ReliabilityChecks

/**
 * Warning shown above the alarm list whenever something would stop an alarm from
 * firing. An alarm app fails invisibly — the user finds out the morning it matters —
 * so the state is surfaced on the main screen rather than only inside a settings page.
 *
 * Re-checked on every resume, because the user typically leaves to change a system
 * setting and comes back.
 */
@Composable
fun ReliabilityBanner(onOpenReliability: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val checks = remember { ReliabilityChecks(context.applicationContext, AppGraph.scheduler) }
    var problemCount by remember { mutableStateOf(0) }

    fun refresh() {
        problemCount = checks.runAll().count { it.status == CheckStatus.ACTION_NEEDED }
    }

    RequestNotificationPermission { refresh() }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    if (problemCount == 0) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenReliability),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = stringResource(R.string.reliability_banner_title, problemCount),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.reliability_banner_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * Asks for notification permission once, on first launch.
 *
 * This matters more than it looks: on Android 13+ a sideloaded app starts with
 * notifications denied, and the alarm's full-screen screen is delivered through a
 * notification. Without the permission a screen-on alarm still vibrates, but the
 * Dismiss and Snooze buttons never appear.
 */
@Composable
private fun RequestNotificationPermission(onResult: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onResult() }

    LaunchedEffect(Unit) {
        if (asked || context.hasNotificationPermission()) return@LaunchedEffect
        asked = true
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
