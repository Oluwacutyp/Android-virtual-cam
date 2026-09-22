package com.androidvirtualcam.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.androidvirtualcam.ui.screens.*
import com.androidvirtualcam.ui.viewmodels.*

sealed class Screen(val route: String) {
    object Main : Screen("main")
    object NodeEditor : Screen("node_editor")
    object Voice : Screen("voice")
    object Stream : Screen("stream")
    object Scenes : Screen("scenes")
    object Settings : Screen("settings")
}

@Composable
fun BroadcastNavGraph(
    navController: NavHostController,
    mainViewModel: MainViewModel,
    nodeGraphViewModel: NodeGraphViewModel,
    voiceViewModel: VoiceViewModel,
    streamViewModel: StreamViewModel,
    sceneViewModel: SceneViewModel,
    settingsViewModel: SettingsViewModel,
    screenCaptureViewModel: ScreenCaptureViewModel,
    teleprompterViewModel: TeleprompterViewModel,
    webControlViewModel: WebControlViewModel,
    instantReplayViewModel: InstantReplayViewModel,
    beautyViewModel: BeautyViewModel,
    audioSourceViewModel: AudioSourceViewModel
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Main.route
    ) {
        composable(Screen.Main.route) {
            MainScreen(
                mainViewModel = mainViewModel,
                screenCaptureViewModel = screenCaptureViewModel,
                teleprompterViewModel = teleprompterViewModel,
                webControlViewModel = webControlViewModel,
                instantReplayViewModel = instantReplayViewModel,
                beautyViewModel = beautyViewModel,
                audioSourceViewModel = audioSourceViewModel,
                onNavigateToNodeEditor = { navController.navigate(Screen.NodeEditor.route) },
                onNavigateToVoice = { navController.navigate(Screen.Voice.route) },
                onNavigateToStream = { navController.navigate(Screen.Stream.route) },
                onNavigateToScenes = { navController.navigate(Screen.Scenes.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.NodeEditor.route) {
            NodeGraphEditorScreen(
                viewModel = nodeGraphViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Voice.route) {
            VoicePanelScreen(
                viewModel = voiceViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Stream.route) {
            StreamPanelScreen(
                viewModel = streamViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Scenes.route) {
            SceneDrawerScreen(
                viewModel = sceneViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = settingsViewModel,
                audioSourceViewModel = audioSourceViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
