package com.example.mobile_assistant

internal data class UiSignal(
    val revision: Long,
    val foregroundPackage: String?,
    val lastEventUptimeMs: Long
) {
    fun changedFrom(other: UiSignal?): Boolean {
        if (other == null) return revision > 0 || !foregroundPackage.isNullOrBlank()
        return revision != other.revision || foregroundPackage != other.foregroundPackage
    }

    fun isSettled(nowUptimeMs: Long, settleWindowMs: Long): Boolean {
        if (settleWindowMs <= 0L) return true
        return nowUptimeMs - lastEventUptimeMs >= settleWindowMs
    }
}
