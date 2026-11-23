package app.farol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.farol.ui.FarolApp
import app.farol.ui.MainViewModel
import app.farol.ui.theme.FarolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
            val state by vm.state.collectAsStateWithLifecycle()
            // Toda vez que o app é aberto ou volta para a frente: busca a edição mais nova.
            LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onAppForeground() }
            FarolTheme(state.theme) {
                FarolApp(vm, state)
            }
        }
    }
}
