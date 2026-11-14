package com.cue.offlinebrain.overlay

import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Installs the owners ComposeView needs when it is hosted outside an Activity decor tree. */
internal object OverlayComposeOwners {
    fun install(view: View, lifecycleOwner: LifecycleOwner, savedStateOwner: SavedStateRegistryOwner) {
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(savedStateOwner)
    }
}
