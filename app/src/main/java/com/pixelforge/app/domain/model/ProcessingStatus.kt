package com.pixelforge.app.domain.model

/**
 * Processing status sealed class for tracking upscale operations.
 */
sealed class ProcessingStatus {
    data object Idle : ProcessingStatus()
    data class Processing(val progress: Float) : ProcessingStatus()
    data class Completed(val outputUri: String) : ProcessingStatus()
    data class Failed(val error: String) : ProcessingStatus()
    data object Cancelled : ProcessingStatus()
}
