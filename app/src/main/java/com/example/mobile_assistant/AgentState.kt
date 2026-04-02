package com.example.mobile_assistant

import android.graphics.Bitmap

data class AgentState(
    val currentGoal: String,
    val plan: List<PlanStep>,
    val nextSteps: List<NextStep>,
    val completedSteps: List<Int>,
    val memory: List<MemoryEntry>,
    val lastScreenshot: Bitmap?,
    val lastTree: Map<String, NodeFingerprint>,
    val lastObservation: ScreenObservationState?,
    val needsUserInput: Boolean,
    val pendingQuestion: String?
)

data class ScreenObservationState(
    val revision: Long?,
    val packageName: String?,
    val screenshotWidth: Int?,
    val screenshotHeight: Int?,
    val screenWidth: Int?,
    val screenHeight: Int?,
    val screenLeft: Int?,
    val screenTop: Int?
) {
    internal fun fullScreenBounds(): ScreenBounds? {
        val width = screenWidth?.takeIf { it > 0 } ?: return null
        val height = screenHeight?.takeIf { it > 0 } ?: return null
        return ScreenBounds(width = width, height = height)
    }

    internal fun capturedWindowBounds(): ScreenBounds? {
        val width = screenWidth?.takeIf { it > 0 } ?: return null
        val height = screenHeight?.takeIf { it > 0 } ?: return null
        return ScreenBounds(
            width = width,
            height = height,
            left = screenLeft ?: 0,
            top = screenTop ?: 0
        )
    }
}

data class NextStep(
    val done: Boolean,
    val text: String
)

data class PlanStep(
    val id: Int,
    val action: String,
    val dependsOn: String? = null,
    val expectedOutcome: String,
    val status: StepStatus
)

data class MemoryEntry(
    val action: String,
    val result: String
)

enum class StepStatus {
    PENDING,
    ACTIVE,
    DONE,
    FAILED
}
