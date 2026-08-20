package com.faybish.vibealarm.ui.alarms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faybish.vibealarm.R
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.ui.format.currentLocale
import com.faybish.vibealarm.ui.format.alarmDescription
import com.faybish.vibealarm.ui.format.triggerAnnouncement
import com.faybish.vibealarm.ui.update.UpdateDialogHost
import com.faybish.vibealarm.ui.update.UpdateViewModel
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.launch

/**
 * The app's home screen, modelled on the alarm tab of Google's Clock: a list of
 * cards that expand in place for editing, and a "+" button that opens a time picker.
 *
 * Editing is deliberate here: the open card holds a draft, and every way out of it —
 * collapsing it, opening another one, the back gesture — goes through the unsaved-changes
 * question first. Every save answers with when the alarm will actually ring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    viewModel: AlarmListViewModel,
    updateViewModel: UpdateViewModel,
    onOpenPatterns: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickPatternFor: (Long) -> Unit,
) {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val patternNames by viewModel.patternNames.collectAsStateWithLifecycle()
    val snoozed by viewModel.snoozed.collectAsStateWithLifecycle()
    val notices by viewModel.notices.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val dirty by viewModel.draftDirty.collectAsStateWithLifecycle()

    // Saveable, not just remembered: picking a pattern navigates away, and a plain
    // remember is gone by the time the user comes back — they returned to a collapsed
    // list and had to find their alarm again. The draft itself lives in the view model,
    // so restoring which card was open restores the whole editing session.
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }

    // What to do once the unsaved-changes question has been answered.
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }

    // What a long press (or the card's delete button) is asking about. One value, because
    // the two dialogs are never open at once, and an id rather than an entity: the list can
    // change underneath a dialog, and acting on a stale copy would duplicate or delete
    // something the user is no longer looking at.
    var pending by remember { mutableStateOf<PendingAlarmAction?>(null) }

    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val locale = currentLocale()

    fun announce(alarm: AlarmEntity, trigger: Instant?) {
        scope.launch {
            // Two saves in a row should read as two answers, not a queue.
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(triggerAnnouncement(context, locale, alarm.enabled, trigger))
        }
    }

    /**
     * Saving finishes the job: the card closes, the list comes back to the top, and the
     * bubble says when the alarm will ring. Leaving an expanded form open after a save
     * reads as "something is still pending" when nothing is.
     */
    fun saveOpenCard(then: () -> Unit = {}) {
        expandedId = null
        // `then` runs only once the row is written. Duplicating straight after a save read
        // the alarm back before the save landed, so the copy carried the old time while the
        // original carried the new one — and the copy is the card the user is handed.
        viewModel.commitDraft { saved, trigger ->
            announce(saved, trigger)
            then()
        }
        scope.launch { listState.animateScrollToItem(0) }
    }

    /** Leaving the open card is the only moment an edit can be lost, so it asks first. */
    fun leaveEditor(then: () -> Unit) {
        if (dirty) {
            pendingLeave = then
        } else {
            viewModel.endEdit()
            then()
        }
    }

    // The update check runs on every open of the app. It cannot delay this screen:
    // the dialog only appears if and when GitHub answers with something newer.
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { updateViewModel.checkOnOpen() }

    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)

    BackHandler(enabled = dirty) { pendingLeave = { expandedId = null } }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.title_alarms)) },
                actions = {
                    IconButton(onClick = onOpenPatterns) {
                        Icon(Icons.Filled.Vibration, stringResource(R.string.title_patterns))
                    }
                    IconButton(onClick = onOpenReliability) {
                        Icon(Icons.Filled.Shield, stringResource(R.string.title_reliability))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.title_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // Hidden while a card is open: an expanded card is a form, and the button
            // floats exactly over its last rows — including save. Google Clock hides it
            // for the same reason.
            if (expandedId == null) {
                FloatingActionButton(onClick = { showTimePicker = true }) {
                    Icon(Icons.Filled.Add, stringResource(R.string.action_add_alarm))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
                start = 12.dp,
                end = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Above everything: a morning that already went wrong outranks one that is
            // still coming, and this is the answer to the red dot that brought the user
            // here in the first place.
            items(notices, key = { "notice-${it.instanceId}" }) { notice ->
                MissedNoticeBanner(
                    notice = notice,
                    onAcknowledge = { viewModel.acknowledgeNotice(notice) },
                )
            }

            // A snooze that is about to ring again is the next most time-critical thing.
            items(snoozed, key = { "snoozed-${it.instanceId}" }) { ring ->
                SnoozedBanner(
                    label = ring.label,
                    ringsAt = ring.ringsAt,
                    remainingSnoozes = ring.remainingSnoozes,
                    onCancel = {
                        viewModel.cancelSnooze(ring) { alarm, trigger ->
                            announce(alarm, trigger)
                        }
                    },
                )
            }

            item { ReliabilityBanner(onOpenReliability = onOpenReliability) }

            if (alarms.isEmpty()) {
                item { EmptyState() }
            } else {
                items(alarms, key = { it.id }) { alarm ->
                    val cardDraft = draft?.takeIf { it.id == alarm.id && expandedId == alarm.id }
                    val shown = cardDraft ?: alarm
                    AlarmCard(
                        stored = alarm,
                        draft = cardDraft,
                        dirty = dirty && cardDraft != null,
                        schedule = viewModel.scheduleOf(shown),
                        nextTrigger = viewModel.nextTrigger(alarm),
                        patternName = shown.patternId?.let { patternNames[it] },
                        expanded = expandedId == alarm.id,
                        onExpandToggle = {
                            if (expandedId == alarm.id) {
                                leaveEditor { expandedId = null }
                            } else {
                                leaveEditor {
                                    expandedId = alarm.id
                                    viewModel.beginEdit(alarm)
                                }
                            }
                        },
                        onEnabledChange = { enabled ->
                            viewModel.setEnabled(alarm.id, enabled) { saved, trigger ->
                                // Switching an alarm off needs no confirmation; switching
                                // one on is a promise about the morning.
                                if (enabled) announce(saved, trigger)
                            }
                        },
                        onAlarmChange = viewModel::updateDraft,
                        onScheduleChange = viewModel::updateDraftSchedule,
                        onSave = { saveOpenCard() },
                        onDiscard = viewModel::resetDraft,
                        onPickPattern = { onPickPatternFor(alarm.id) },
                        onPreviewVibration = { viewModel.previewVibration(shown) },
                        onPreviewSound = { viewModel.previewSound(shown) },
                        onLongPress = {
                            pending = PendingAlarmAction.Choose(alarm.id)
                        },
                        // The same confirmation the long press gets: this button sits under
                        // the alarm the user was just editing, and deletion cannot be undone.
                        onDelete = {
                            pending = PendingAlarmAction.ConfirmDelete(alarm.id)
                        },
                    )
                }
            }
        }
    }

    // A dialog whose alarm has vanished renders nothing rather than clearing the state from
    // inside composition — a write the same pass has already read is a recomposition it does
    // not need. The description is remembered because formatting it reads the device's clock
    // setting and compiles a pattern.
    val pendingTarget = pending?.let { action -> alarms.firstOrNull { it.id == action.alarmId } }
    val pendingName = remember(pendingTarget, locale) {
        pendingTarget?.let { alarmDescription(context, locale, it) }
    }

    if (pendingTarget != null && pendingName != null) {
        when (pending) {
            is PendingAlarmAction.Choose -> AlarmActionsDialog(
                alarmDescription = pendingName,
                onDuplicate = {
                    val id = pendingTarget.id
                    pending = null
                    // Duplicating takes over the draft, so an open card with unsaved edits
                    // has to be settled first — the same question as opening another card.
                    leaveEditor {
                        expandedId = null
                        viewModel.duplicate(id, context.getString(R.string.duplicate_suffix)) { copy ->
                            // No scroll: alarms are ordered by time, so the copy appears
                            // right below its original, which is where the user is already
                            // looking. Jumping to the top would hide the card just opened.
                            expandedId = copy.id
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar(context.getString(R.string.duplicate_created))
                            }
                        }
                    }
                },
                onDelete = { pending = PendingAlarmAction.ConfirmDelete(pendingTarget.id) },
                onDismiss = { pending = null },
            )

            is PendingAlarmAction.ConfirmDelete -> ConfirmDeleteDialog(
                alarmDescription = pendingName,
                onConfirm = {
                    val id = pendingTarget.id
                    pending = null
                    // The card being deleted may be the open one; its draft has to go with
                    // it, or a later save would write the alarm back.
                    if (expandedId == id) {
                        expandedId = null
                        viewModel.endEdit()
                    }
                    viewModel.delete(id)
                },
                onDismiss = { pending = null },
            )

            null -> Unit
        }
    }

    pendingLeave?.let { leave ->
        UnsavedChangesDialog(
            onSave = {
                pendingLeave = null
                saveOpenCard(then = leave)
            },
            onDiscard = {
                pendingLeave = null
                viewModel.endEdit()
                leave()
            },
            onKeepEditing = { pendingLeave = null },
        )
    }

    UpdateDialogHost(
        state = updateState,
        installedVersion = updateViewModel.installedVersion,
        onDownload = updateViewModel::download,
        onSkip = updateViewModel::skip,
        onDismiss = updateViewModel::dismiss,
        onOpenInstallSettings = updateViewModel::openInstallPermissionSettings,
    )

    if (showTimePicker) {
        val now = LocalTime.now()
        TimePickerDialog(
            initialHour = now.hour,
            initialMinute = now.minute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                showTimePicker = false
                // A new alarm is armed at the time just picked and opens for editing, so
                // the confirmation is about the alarm that already exists.
                viewModel.addAlarm(LocalTime.of(hour, minute)) { saved, trigger ->
                    expandedId = saved.id
                    announce(saved, trigger)
                }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.empty_alarms_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.empty_alarms_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
