package com.voiceprompter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.voiceprompter.prompter.PrompterViewModel
import com.voiceprompter.ui.PrompterScreen
import com.voiceprompter.ui.ScriptEditorScreen
import com.voiceprompter.ui.ScriptListScreen
import com.voiceprompter.ui.ScriptsViewModel
import com.voiceprompter.ui.theme.VoicePrompterTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var hasMicPermission by mutableStateOf(false)
    private var prompterViewModel: PrompterViewModel? = null

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasMicPermission = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            VoicePrompterTheme {
                val navController = rememberNavController()
                val scriptsViewModel: ScriptsViewModel = viewModel()
                val prompter: PrompterViewModel = viewModel()
                prompterViewModel = prompter

                NavHost(navController, startDestination = "scripts") {

                    composable("scripts") {
                        ScriptListScreen(
                            viewModel = scriptsViewModel,
                            onEdit = { id -> navController.navigate("editor/$id") },
                            onRun = { id -> navController.navigate("prompter/$id") },
                        )
                    }

                    composable(
                        "editor/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        ScriptEditorScreen(
                            viewModel = scriptsViewModel,
                            scriptId = entry.arguments?.getLong("id") ?: 0L,
                            onDone = { navController.popBackStack() },
                        )
                    }

                    composable(
                        "prompter/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: 0L
                        androidx.compose.runtime.LaunchedEffect(id) {
                            lifecycleScope.launch {
                                scriptsViewModel.byId(id)?.let { prompter.loadScript(it.body) }
                            }
                        }
                        PrompterScreen(
                            viewModel = prompter,
                            hasMicPermission = hasMicPermission,
                            onRequestMicPermission = {
                                requestMic.launch(Manifest.permission.RECORD_AUDIO)
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Volume keys scroll the script, per the spec. This only works while the
     * app is in the foreground — Android does not hand volume keys to a
     * backgrounded app, so a Bluetooth clicker that sends media or page keys
     * is the route for hands-free control from a distance.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val vm = prompterViewModel ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                vm.previous(); true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                vm.next(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
