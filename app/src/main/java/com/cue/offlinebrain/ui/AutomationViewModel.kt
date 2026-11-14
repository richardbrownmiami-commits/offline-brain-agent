package com.cue.offlinebrain.ui

import androidx.lifecycle.ViewModel

/** Feature-scoped facade over the activity's single shared coordinator. */
class AutomationViewModel(internal val coordinator: BrainViewModel) : ViewModel()
