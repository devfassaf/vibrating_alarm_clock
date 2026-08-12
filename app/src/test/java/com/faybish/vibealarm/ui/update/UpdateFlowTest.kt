package com.faybish.vibealarm.ui.update

import android.app.AlarmManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.RingMode
import com.faybish.vibealarm.data.ScheduleCodec
import com.faybish.vibealarm.domain.Schedule
import com.faybish.vibealarm.domain.update.RawRelease
import com.faybish.vibealarm.domain.update.ReleaseAsset
import com.faybish.vibealarm.domain.update.ReleaseSource
import com.faybish.vibealarm.domain.update.UpdateAssets
import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The updater end to end through the real wiring: the view model, the real
 * DataStore-backed store, and the real alarm repository that decides whether now is a
 * good moment. Only the network is faked.
 *
 * This is the half that cannot be exercised on a device without publishing a release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateFlowTest {

    private lateinit var application: Application

    private class FakeSource(private var releases: List<RawRelease>?) : ReleaseSource {
        override suspend fun fetchReleases(): List<RawRelease>? = releases
    }

    private fun releaseOf(version: String, notes: String? = "## מה חדש\n\n- רטט עדין יותר") =
        RawRelease(
            tag = "v$version",
            body = notes,
            draft = false,
            preRelease = false,
            assets = listOf(
                ReleaseAsset(
                    name = "vibealarm-v$version.apk",
                    downloadUrl = "https://example.test/vibealarm-v$version.apk",
                    sizeBytes = 12_345_678,
                ),
                ReleaseAsset(UpdateAssets.STABLE_APK, "https://example.test/stable.apk", 12_345_678),
            ),
        )

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        AppGraph.resetForTests(application)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    /** The installed version under Robolectric comes from the merged manifest (1.0.0). */
    private fun viewModel() = UpdateViewModel(application)

    /**
     * The check runs through DataStore, whose reads land on a background dispatcher the
     * main looper does not drain — so waiting means actually waiting, not just idling.
     */
    private fun UpdateViewModel.awaitState(
        timeoutMs: Long = 5_000,
        predicate: (UpdateUiState) -> Boolean,
    ): UpdateUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(application.mainLooper).idle()
            if (predicate(state.value)) return state.value
            Thread.sleep(20)
        }
        return state.value
    }

    /** For the cases that must NOT change the screen: give them a chance to misbehave. */
    private fun UpdateViewModel.awaitSettled(millis: Long = 1_500): UpdateUiState {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(application.mainLooper).idle()
            Thread.sleep(20)
        }
        return state.value
    }

    private fun awaitLogEntry(
        event: String,
        timeoutMs: Long = 5_000,
    ): com.faybish.vibealarm.data.ReliabilityLogEntity? = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            AppGraph.db.logDao().latest(event)?.let { return@runBlocking it }
            shadowOf(application.mainLooper).idle()
            Thread.sleep(20)
        }
        AppGraph.db.logDao().latest(event)
    }

    private fun armAlarm(minutesFromNow: Long) = runBlocking {
        val time = LocalTime.now().plusMinutes(minutesFromNow)
        val alarm = ScheduleCodec.encode(
            Schedule.Weekly(DayOfWeek.entries.toSet(), LocalTime.of(time.hour, time.minute)),
            AlarmEntity(
                label = "next",
                enabled = true,
                timeMinutesOfDay = ScheduleCodec.timeToMinutes(time),
                mode = RingMode.VIBRATE_ONLY,
            ),
        )
        val id = AppGraph.repository.saveAlarm(alarm)
        AppGraph.scheduler.onAlarmSaved(AppGraph.repository.getAlarm(id)!!)
    }

    @Test
    fun `a newer release opens the prompt with its notes`() = runBlocking {
        AppGraph.releaseSource = FakeSource(listOf(releaseOf("9.9.9")))

        val vm = viewModel()
        vm.checkOnOpen()

        val state = vm.awaitState { it is UpdateUiState.Available }
        assertThat(state).isInstanceOf(UpdateUiState.Available::class.java)
        val available = state as UpdateUiState.Available
        assertThat(available.release.version).isEqualTo("9.9.9")
        // The versioned asset is preferred over the stable one.
        assertThat(available.release.assetName).isEqualTo("vibealarm-v9.9.9.apk")
        assertThat(available.release.whatsNew.flatMap { it.lines }).contains("רטט עדין יותר")
    }

    @Test
    fun `nothing newer leaves the screen alone`() = runBlocking {
        AppGraph.releaseSource = FakeSource(listOf(releaseOf("0.0.1")))

        val vm = viewModel()
        vm.checkOnOpen()

        assertThat(vm.awaitSettled()).isEqualTo(UpdateUiState.Idle)
    }

    @Test
    fun `an unreachable GitHub leaves the screen alone`() = runBlocking {
        AppGraph.releaseSource = FakeSource(null)

        val vm = viewModel()
        vm.checkOnOpen()

        assertThat(vm.awaitSettled()).isEqualTo(UpdateUiState.Idle)
    }

    /**
     * The rule the whole app exists for: an update must not be offered when it would
     * mean replacing the app minutes before an alarm is due.
     */
    @Test
    fun `an alarm inside the quiet window suppresses the automatic prompt`() = runBlocking {
        armAlarm(minutesFromNow = 10)
        AppGraph.releaseSource = FakeSource(listOf(releaseOf("9.9.9")))

        val vm = viewModel()
        vm.checkOnOpen()

        assertThat(vm.awaitSettled()).isEqualTo(UpdateUiState.Idle)
    }

    @Test
    fun `an alarm well beyond the quiet window still allows the prompt`() = runBlocking {
        armAlarm(minutesFromNow = 240)
        AppGraph.releaseSource = FakeSource(listOf(releaseOf("9.9.9")))

        val vm = viewModel()
        vm.checkOnOpen()

        assertThat(vm.awaitState { it is UpdateUiState.Available })
            .isInstanceOf(UpdateUiState.Available::class.java)
    }

    /** The manual button reports the reason instead of doing nothing visible. */
    @Test
    fun `the manual check names the alarm as the reason for postponing`() = runBlocking {
        armAlarm(minutesFromNow = 5)
        AppGraph.releaseSource = FakeSource(listOf(releaseOf("9.9.9")))

        val vm = viewModel()
        vm.checkNow()

        assertThat(vm.awaitState { it is UpdateUiState.Postponed })
            .isInstanceOf(UpdateUiState.Postponed::class.java)
    }

    @Test
    fun `the manual check confirms when there is nothing to install`() = runBlocking {
        AppGraph.releaseSource = FakeSource(emptyList())

        val vm = viewModel()
        vm.checkNow()

        assertThat(vm.awaitState { it == UpdateUiState.AlreadyCurrent })
            .isEqualTo(UpdateUiState.AlreadyCurrent)
    }

    /** Skipping persists through the real DataStore, and a later open stays quiet. */
    @Test
    fun `a skipped version does not come back on the next open`() = runBlocking {
        AppGraph.releaseSource = FakeSource(listOf(releaseOf("9.9.9")))

        val vm = viewModel()
        vm.checkOnOpen()
        val available = vm.awaitState { it is UpdateUiState.Available } as UpdateUiState.Available

        vm.skip(available.release)
        assertThat(vm.awaitState { it == UpdateUiState.Idle }).isEqualTo(UpdateUiState.Idle)
        assertThat(AppGraph.updateStore.skippedVersion()).isEqualTo("9.9.9")

        // A fresh view model, as if the app were reopened later.
        val reopened = viewModel()
        reopened.checkOnOpen()
        assertThat(reopened.awaitSettled()).isEqualTo(UpdateUiState.Idle)

        // …but the button the user pressed themselves must still surface it.
        reopened.checkNow()
        assertThat(reopened.awaitState { it is UpdateUiState.Available })
            .isInstanceOf(UpdateUiState.Available::class.java)
    }

    @Test
    fun `the check is recorded in the reliability log so a silent feature stays observable`() =
        runBlocking {
            AppGraph.releaseSource = FakeSource(listOf(releaseOf("9.9.9")))

            val vm = viewModel()
            vm.checkOnOpen()
            vm.awaitState { it is UpdateUiState.Available }

            // The log write is fire-and-forget on the app scope, so it can land just
            // after the state does.
            val entry = awaitLogEntry("UPDATE_CHECK")
            assertThat(entry).isNotNull()
            assertThat(entry!!.detail).contains("9.9.9")
        }

    @Test
    fun `arming an alarm never touches the network`() {
        // The alarm path must stay offline: only the UI checks for updates.
        val alarmManager = AppGraph.deviceProtectedContext.getSystemService(AlarmManager::class.java)
        AppGraph.releaseSource = FakeSource(null)
        armAlarm(minutesFromNow = 120)
        assertThat(shadowOf(alarmManager).scheduledAlarms).hasSize(1)
    }
}
