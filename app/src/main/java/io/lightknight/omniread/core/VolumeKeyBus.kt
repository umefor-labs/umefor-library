package io.lightknight.omniread.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Coordinates hardware volume key page turns (+1 = next, -1 = previous)
 * when an active novel or comic reader enables volume key navigation.
 */
object VolumeKeyBus {
    @Volatile
    var interceptVolumeKeys: Boolean = false

    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val events: SharedFlow<Int> = _events.asSharedFlow()

    fun emitPageTurn(delta: Int) {
        _events.tryEmit(delta)
    }
}
