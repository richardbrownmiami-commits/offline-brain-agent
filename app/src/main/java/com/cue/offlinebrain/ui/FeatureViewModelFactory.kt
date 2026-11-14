package com.cue.offlinebrain.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Creates feature adapters around the activity's single shared BrainViewModel. */
class FeatureViewModelFactory(private val coordinator: BrainViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val instance: ViewModel = when (modelClass) {
            ChatViewModel::class.java -> ChatViewModel(coordinator)
            AlarmViewModel::class.java -> AlarmViewModel(coordinator)
            NotificationViewModel::class.java -> NotificationViewModel(coordinator)
            AutomationViewModel::class.java -> AutomationViewModel(coordinator)
            SecurityViewModel::class.java -> SecurityViewModel(coordinator)
            VoiceViewModel::class.java -> VoiceViewModel(coordinator)
            DiagnosticsViewModel::class.java -> DiagnosticsViewModel(coordinator)
            else -> throw IllegalArgumentException("Unsupported feature ViewModel: ${modelClass.name}")
        }
        return instance as T
    }
}
