package noline.zhouzhou.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import noline.zhouzhou.AppContainer

@Composable
inline fun <reified VM : ViewModel> typechoViewModel(
    appContainer: AppContainer,
    noinline initializer: (AppContainer) -> VM
): VM = viewModel(factory = viewModelFactory {
    initializer { initializer(appContainer) }
})
