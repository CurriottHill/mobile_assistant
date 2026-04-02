package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolUiSupportTest {
    @Test
    fun uiSnapshot_fromExtractsPackageAndNodeRefs() {
        val snapshot = UiSnapshot.from(
            foregroundPackage = null,
            screenDump = """
                [App: com.example.app]
                Button [nf_abc123]
                Field [nf_def456]
            """.trimIndent(),
            revision = 7L
        )

        assertNotNull(snapshot)
        assertEquals("com.example.app", snapshot?.foregroundPackage)
        assertTrue(snapshot?.containsNodeRef("nf_abc123") == true)
        assertTrue(snapshot?.containsNodeRef("nf_def456") == true)
    }

    @Test
    fun deltaSummaryFrom_reportsPackageAndTreeChanges() {
        val before = UiSnapshot.from(
            foregroundPackage = "com.example.before",
            screenDump = "[App: com.example.before]\nButton [nf_abc123]"
        )
        val after = UiSnapshot.from(
            foregroundPackage = "com.example.after",
            screenDump = "[App: com.example.after]\nButton [nf_def456]"
        )

        assertEquals(
            "package com.example.before -> com.example.after, +1 nodes, -1 nodes",
            after?.deltaSummaryFrom(before)
        )
    }

    @Test
    fun isTransientForegroundPackage_matchesSystemIntermediates() {
        assertTrue(AgentToolUiSupport.isTransientForegroundPackage("android"))
        assertTrue(AgentToolUiSupport.isTransientForegroundPackage("com.android.permissioncontroller"))
        assertFalse(AgentToolUiSupport.isTransientForegroundPackage("com.example.realapp"))
    }

    @Test
    fun preferredForegroundPackage_prefersLiveSignalOverStaleSnapshot() {
        val signal = UiSignal(
            revision = 9L,
            foregroundPackage = "com.android.chrome",
            lastEventUptimeMs = 123L
        )
        val snapshot = UiSnapshot.from(
            foregroundPackage = "com.example.oldapp",
            screenDump = "[App: com.example.oldapp]\nOld content"
        )

        assertEquals(
            "com.android.chrome",
            AgentToolUiSupport.preferredForegroundPackage(signal, snapshot)
        )
    }

    @Test
    fun preferredForegroundPackage_fallsBackToSnapshotWhenSignalMissing() {
        val snapshot = UiSnapshot.from(
            foregroundPackage = "com.android.chrome",
            screenDump = "[App: com.android.chrome]\nPage"
        )

        assertEquals(
            "com.android.chrome",
            AgentToolUiSupport.preferredForegroundPackage(null, snapshot)
        )
        assertNull(AgentToolUiSupport.preferredForegroundPackage(null, null))
    }
}
