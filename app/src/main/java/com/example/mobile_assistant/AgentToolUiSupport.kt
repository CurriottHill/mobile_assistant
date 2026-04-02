package com.example.mobile_assistant

import org.json.JSONObject

internal data class UiSnapshot(
    val revision: Long?,
    val foregroundPackage: String?,
    val screenDump: String?,
    val nodeRefs: Set<String>
) {
    fun contentChangedFrom(other: UiSnapshot?): Boolean {
        if (other == null) {
            return !screenDump.isNullOrBlank() || !foregroundPackage.isNullOrBlank()
        }
        return normalizedDump(screenDump) != normalizedDump(other.screenDump) ||
            foregroundPackage != other.foregroundPackage
    }

    fun containsNodeRef(nodeRef: String?): Boolean {
        return !nodeRef.isNullOrBlank() && nodeRefs.contains(nodeRef.trim())
    }

    fun containsText(text: String?): Boolean {
        return !text.isNullOrBlank() &&
            screenDump?.contains(text.trim(), ignoreCase = true) == true
    }

    fun deltaSummaryFrom(other: UiSnapshot?): String? {
        if (other == null) return null

        val packageChanged = foregroundPackage != other.foregroundPackage
        val addedNodes = nodeRefs.subtract(other.nodeRefs).size
        val removedNodes = other.nodeRefs.subtract(nodeRefs).size
        val dumpChanged = normalizedDump(screenDump) != normalizedDump(other.screenDump)

        if (!packageChanged && !dumpChanged) return null

        return buildString {
            if (packageChanged) {
                append("package ")
                append(other.foregroundPackage ?: "unknown")
                append(" -> ")
                append(foregroundPackage ?: "unknown")
            }
            if (addedNodes > 0 || removedNodes > 0) {
                if (isNotEmpty()) append(", ")
                append("+")
                append(addedNodes)
                append(" nodes, -")
                append(removedNodes)
                append(" nodes")
            } else if (dumpChanged) {
                if (isNotEmpty()) append(", ")
                append("tree content updated")
            }
        }.ifBlank { null }
    }

    companion object {
        private val NODE_REF_REGEX = Regex("""\[(nf_[a-f0-9]+)]""")
        private val PACKAGE_LINE_REGEX = Regex("""^\[App:\s*(.+)]$""")

        fun from(foregroundPackage: String?, screenDump: String?, revision: Long? = null): UiSnapshot? {
            val normalizedDump = screenDump?.trim()?.ifBlank { null }
            val normalizedPackage = foregroundPackage?.trim()?.ifBlank { null }
                ?: extractPackage(normalizedDump)
            if (normalizedPackage == null && normalizedDump == null) return null

            val refs = normalizedDump?.let { dump ->
                NODE_REF_REGEX.findAll(dump)
                    .map { it.groupValues[1] }
                    .toSet()
            }.orEmpty()

            return UiSnapshot(
                revision = revision,
                foregroundPackage = normalizedPackage,
                screenDump = normalizedDump,
                nodeRefs = refs
            )
        }

        private fun extractPackage(screenDump: String?): String? {
            val firstLine = screenDump?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            return PACKAGE_LINE_REGEX.matchEntire(firstLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.ifBlank { null }
        }

        private fun normalizedDump(value: String?): String {
            return value?.trim().orEmpty()
        }
    }
}

internal sealed interface ActionNodeResolution {
    data class Resolved(
        val requestedNodeRef: String,
        val resolvedNodeRef: String,
        val snapshot: UiSnapshot,
        val wasRemapped: Boolean
    ) : ActionNodeResolution

    data class Failed(val error: String) : ActionNodeResolution
}

internal sealed interface UiWaitSignal {
    object KeepWaiting : UiWaitSignal
    data class Success(val reason: String) : UiWaitSignal
    data class WrongTarget(val reason: String) : UiWaitSignal
}

internal sealed interface UiWaitOutcome {
    val snapshot: UiSnapshot?
    val reason: String

    data class Success(
        override val snapshot: UiSnapshot?,
        override val reason: String
    ) : UiWaitOutcome

    data class WrongTarget(
        override val snapshot: UiSnapshot?,
        override val reason: String
    ) : UiWaitOutcome

    data class Timeout(
        override val snapshot: UiSnapshot?,
        override val reason: String
    ) : UiWaitOutcome
}

internal object AgentToolUiSupport {
    fun readUiSignal(
        uiSignalReader: () -> UiSignal?,
        foregroundPackageReader: () -> String?
    ): UiSignal? {
        val signal = uiSignalReader()
        val foregroundPackage = signal?.foregroundPackage?.trim()?.ifBlank { null }
            ?: foregroundPackageReader()?.trim()?.ifBlank { null }

        if (signal == null && foregroundPackage == null) {
            return null
        }

        return UiSignal(
            revision = signal?.revision ?: 0L,
            foregroundPackage = foregroundPackage,
            lastEventUptimeMs = signal?.lastEventUptimeMs ?: android.os.SystemClock.uptimeMillis()
        )
    }

    fun preferredForegroundPackage(signal: UiSignal?, snapshot: UiSnapshot?): String? {
        return signal?.foregroundPackage?.trim()?.ifBlank { null }
            ?: snapshot?.foregroundPackage?.trim()?.ifBlank { null }
    }

    fun currentKnownTreeSnapshot(
        latestCapturedTreeSnapshot: UiSnapshot?,
        signal: UiSignal?
    ): UiSnapshot? {
        val snapshot = latestCapturedTreeSnapshot ?: return null

        return when {
            signal == null -> snapshot.takeIf { !it.screenDump.isNullOrBlank() }
            snapshot.revision != null && snapshot.revision == signal.revision -> snapshot
            else -> null
        }
    }

    fun reconcileLaunchOutcome(
        preliminaryOutcome: UiWaitOutcome,
        beforeSnapshot: UiSnapshot?,
        afterSnapshot: UiSnapshot?,
        expectedPackage: String?,
        requireContentChangeInExpectedPackage: Boolean,
        timeoutReason: String
    ): UiWaitOutcome {
        if (preliminaryOutcome is UiWaitOutcome.WrongTarget) {
            if (afterSnapshot != null &&
                !expectedPackage.isNullOrBlank() &&
                afterSnapshot.foregroundPackage == expectedPackage &&
                (!requireContentChangeInExpectedPackage || afterSnapshot.contentChangedFrom(beforeSnapshot))
            ) {
                return UiWaitOutcome.Success(
                    snapshot = afterSnapshot,
                    reason = "Foreground package settled on $expectedPackage."
                )
            }
            return UiWaitOutcome.WrongTarget(
                snapshot = afterSnapshot ?: preliminaryOutcome.snapshot,
                reason = preliminaryOutcome.reason
            )
        }

        if (afterSnapshot == null) {
            return preliminaryOutcome
        }

        val packageMatches = expectedPackage.isNullOrBlank() || afterSnapshot.foregroundPackage == expectedPackage
        val contentChanged = afterSnapshot.contentChangedFrom(beforeSnapshot)

        if (packageMatches && (!requireContentChangeInExpectedPackage || contentChanged || beforeSnapshot == null)) {
            val reason = if (expectedPackage.isNullOrBlank()) {
                "Visible screen content changed after launch."
            } else {
                "Foreground package settled on ${afterSnapshot.foregroundPackage ?: expectedPackage}."
            }
            return UiWaitOutcome.Success(
                snapshot = afterSnapshot,
                reason = reason
            )
        }

        if (!expectedPackage.isNullOrBlank() &&
            !afterSnapshot.foregroundPackage.isNullOrBlank() &&
            afterSnapshot.foregroundPackage != expectedPackage &&
            !isTransientForegroundPackage(afterSnapshot.foregroundPackage)
        ) {
            return UiWaitOutcome.WrongTarget(
                snapshot = afterSnapshot,
                reason = "Foreground package settled on ${afterSnapshot.foregroundPackage} instead of $expectedPackage."
            )
        }

        return UiWaitOutcome.Timeout(
            snapshot = afterSnapshot,
            reason = timeoutReason
        )
    }

    fun didOpenDifferentPackage(
        beforeSnapshot: UiSnapshot?,
        beforeSignal: UiSignal?,
        afterSnapshot: UiSnapshot?
    ): Boolean {
        val beforePackage = beforeSnapshot?.foregroundPackage ?: beforeSignal?.foregroundPackage
        val afterPackage = afterSnapshot?.foregroundPackage
        return !afterPackage.isNullOrBlank() && afterPackage != beforePackage
    }

    fun uiSnapshotForSignal(signal: UiSignal?): UiSnapshot? {
        return UiSnapshot.from(
            foregroundPackage = signal?.foregroundPackage,
            screenDump = null,
            revision = signal?.revision
        )
    }

    fun applyWaitOutcome(content: JSONObject, outcome: UiWaitOutcome?) {
        when (outcome) {
            null -> Unit

            is UiWaitOutcome.Success -> {
                content.put("wait_status", "success")
                content.put("wait_reason", outcome.reason)
                outcome.snapshot?.foregroundPackage?.let { content.put("observed_package", it) }
            }

            is UiWaitOutcome.WrongTarget -> {
                content.put("wait_status", "wrong_target")
                content.put("wait_reason", outcome.reason)
                outcome.snapshot?.foregroundPackage?.let { content.put("observed_package", it) }
                content.put("ok", false)
                content.put("error", outcome.reason)
            }

            is UiWaitOutcome.Timeout -> {
                content.put("wait_status", "timeout")
                content.put("wait_reason", outcome.reason)
                outcome.snapshot?.foregroundPackage?.let { content.put("observed_package", it) }
                content.put("ok", false)
                content.put("error", outcome.reason)
            }
        }
    }

    fun isTransientForegroundPackage(packageName: String?): Boolean {
        val normalized = packageName?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        return normalized == "android" ||
            normalized == "com.android.systemui" ||
            normalized.contains("permissioncontroller") ||
            normalized.contains("packageinstaller")
    }
}
