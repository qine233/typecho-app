package com.typecho.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.typecho.app.AppContainer

@Composable
inline fun <reified VM : ViewModel> typechoViewModel(
    appContainer: AppContainer,
    noinline initializer: (AppContainer) -> VM
): VM = viewModel(factory = viewModelFactory {
    initializer { initializer(appContainer) }
})
