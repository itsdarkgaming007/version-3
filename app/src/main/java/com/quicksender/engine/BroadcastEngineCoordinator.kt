package com.quicksender.engine

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean

object BroadcastEngineCoordinator {
    val progressFlow = MutableStateFlow(Pair(0, 0)) // current, total
    val isPaused = AtomicBoolean(false)
    val isCancelled = AtomicBoolean(false)

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
    }

    fun cancel() {
        isCancelled.set(true)
        isPaused.set(false)
    }

    fun reset() {
        isPaused.set(false)
        isCancelled.set(false)
        progressFlow.value = Pair(0, 0)
    }
}
