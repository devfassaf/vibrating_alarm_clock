package com.faybish.vibealarm.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.faybish.vibealarm.alarm.VibrationEngine
import com.faybish.vibealarm.ui.alarms.AlarmListScreen
import com.faybish.vibealarm.ui.patterns.PatternBuilderScreen
import com.faybish.vibealarm.ui.patterns.PatternLibraryScreen
import com.faybish.vibealarm.ui.patterns.PatternViewModel
import com.faybish.vibealarm.ui.patterns.RecorderPadScreen
import com.faybish.vibealarm.ui.reliability.ReliabilityScreen
import com.faybish.vibealarm.ui.reliability.ReliabilityViewModel
import com.faybish.vibealarm.ui.settings.SettingsScreen

private object Routes {
    const val ALARMS = "alarms"
    const val PATTERNS = "patterns?forAlarm={forAlarm}"
    const val BUILDER = "builder?patternId={patternId}"
    const val RECORDER = "recorder"
    const val RELIABILITY = "reliability"
    const val SETTINGS = "settings"

    fun patterns(forAlarmId: Long?) = "patterns?forAlarm=${forAlarmId ?: -1L}"
    fun builder(patternId: Long?) = "builder?patternId=${patternId ?: -1L}"
}

@Composable
fun VibeAlarmApp() {
    val navController = rememberNavController()
    val context = LocalContext.current

    // The pattern screens share one view model so a recording carries into the
    // builder and out to the library without a round-trip through the database.
    val patternViewModel: PatternViewModel = viewModel(
        factory = viewModelFactory { PatternViewModel(VibrationEngine(context)) },
    )

    NavHost(navController = navController, startDestination = Routes.ALARMS) {
        composable(Routes.ALARMS) {
            AlarmListScreen(
                viewModel = viewModel(),
                onOpenPatterns = { navController.navigate(Routes.patterns(null)) },
                onOpenReliability = { navController.navigate(Routes.RELIABILITY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onPickPatternFor = { alarmId -> navController.navigate(Routes.patterns(alarmId)) },
            )
        }

        composable(
            route = Routes.PATTERNS,
            arguments = listOf(
                navArgument("forAlarm") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val forAlarm = entry.arguments?.getLong("forAlarm") ?: -1L
            PatternLibraryScreen(
                viewModel = patternViewModel,
                onBack = { navController.popBackStack() },
                onEdit = { patternId ->
                    patternViewModel.loadDraft(patternId)
                    navController.navigate(Routes.builder(patternId))
                },
                onPicked = if (forAlarm > 0) {
                    { patternId ->
                        patternViewModel.assignToAlarm(forAlarm, patternId)
                        navController.popBackStack()
                    }
                } else {
                    null
                },
            )
        }

        composable(
            route = Routes.BUILDER,
            arguments = listOf(
                navArgument("patternId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) {
            PatternBuilderScreen(
                viewModel = patternViewModel,
                onBack = { navController.popBackStack() },
                onOpenRecorder = { navController.navigate(Routes.RECORDER) },
            )
        }

        composable(Routes.RECORDER) {
            RecorderPadScreen(
                viewModel = patternViewModel,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }

        composable(Routes.RELIABILITY) {
            ReliabilityScreen(
                viewModel = viewModel(
                    factory = viewModelFactory { ReliabilityViewModel(context) },
                ),
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

/** Tiny factory helper so view models can take constructor arguments without DI. */
private fun <T : ViewModel> viewModelFactory(
    create: () -> T,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}
