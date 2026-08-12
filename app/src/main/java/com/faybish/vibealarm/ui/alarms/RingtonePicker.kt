package com.faybish.vibealarm.ui.alarms

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.faybish.vibealarm.R
import com.faybish.vibealarm.ui.components.LabeledRow

/**
 * Row that opens the system ringtone picker, limited to the device's alarm sounds.
 *
 * Using the platform picker means the list is whatever the phone already offers —
 * Samsung's own alarm sounds included — with no storage permission and no file browsing.
 * "Silent" is deliberately not offered: an alarm that makes no sound is the
 * vibration-only mode, chosen a row above.
 */
@Composable
fun RingtonePickerRow(
    currentUri: String?,
    onPicked: (String?) -> Unit,
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked = result.data
            ?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        // Null comes back for both "Default" and (where the platform still offers it)
        // "None". Storing null means the system default, so a sound-mode alarm can never
        // end up silent by accident — silence is the vibration-only mode, one row above.
        onPicked(picked?.toString())
    }

    val title = ringtoneTitle(context, currentUri)

    LabeledRow(
        title = stringResource(R.string.field_ringtone),
        value = title,
        leadingIcon = Icons.Filled.MusicNote,
        onClick = {
            val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.field_ringtone))
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, default)
                // A null column means "the system default", but handing the picker a null
                // existing URI makes it highlight "None" — which reads as silence, the one
                // thing an alarm must never be. Resolve it to the default instead.
                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    currentUri?.takeIf { it.isNotBlank() }?.toUri() ?: default,
                )
            }
            runCatching { launcher.launch(intent) }
        },
    )
}

/**
 * The name to show for a stored ringtone URI.
 *
 * Falls back to "Default" for null, and to the same when the title cannot be read — a
 * sound the user picked from their own storage can become unreadable (deleted, or on an
 * SD card that is not mounted), and a blank row would be worse than an honest default.
 */
@Composable
fun ringtoneTitle(context: Context, uri: String?): String {
    val fallback = stringResource(R.string.ringtone_default)
    return remember(uri) {
        val raw = uri?.takeIf { it.isNotBlank() } ?: return@remember null
        runCatching { RingtoneManager.getRingtone(context, raw.toUri())?.getTitle(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    } ?: fallback
}
