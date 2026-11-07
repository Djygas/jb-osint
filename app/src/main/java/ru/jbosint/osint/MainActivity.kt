package ru.jbosint.osint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.jbosint.osint.ui.AppRoot
import ru.jbosint.osint.ui.AppViewModel
import ru.jbosint.osint.ui.JbTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JbTheme {
                val vm: AppViewModel = viewModel()
                val state by vm.state.collectAsState()
                AppRoot(state = state, vm = vm)
            }
        }
    }
}
