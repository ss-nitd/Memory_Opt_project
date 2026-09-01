package com.example.myapplication.runtime

sealed interface RuntimeState {

    data object Idle : RuntimeState

    data object Running : RuntimeState

    data class Paused(
        val reason: PauseReason
    ) : RuntimeState

    data object Completed : RuntimeState
}

enum class PauseReason {
    USER,
    UI_HIDDEN,
    SYSTEM_MEMORY_LOW,
    SYSTEM_MEMORY_CRITICAL
}