package com.cue.offlinebrain.diagnostics

import androidx.core.content.FileProvider

/** Trivial subclass required for reliable AndroidX FileProvider registration across devices. */
class BrainFileProvider : FileProvider()
