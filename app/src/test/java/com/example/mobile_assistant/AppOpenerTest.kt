package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppOpenerTest {
    @Test
    fun findBestLauncherMatch_doesNotUseSingleCharacterSubstringMatches() {
        val match = AppOpener.findBestLauncherMatch(
            launcherApps = listOf(
                AppOpener.LauncherAppMatch(
                    packageName = "org.mozilla.firefox",
                    label = "Firefox"
                ),
                AppOpener.LauncherAppMatch(
                    packageName = "com.google.android.apps.maps",
                    label = "Google Maps"
                )
            ),
            requestedName = "x"
        )

        assertNull(match)
    }

    @Test
    fun findBestLauncherMatch_matchesXByAliasToTwitterPackage() {
        val match = AppOpener.findBestLauncherMatch(
            launcherApps = listOf(
                AppOpener.LauncherAppMatch(
                    packageName = "com.twitter.android",
                    label = "X"
                )
            ),
            requestedName = "twitter"
        )

        assertEquals("com.twitter.android", match?.packageName)
    }

    @Test
    fun findBestLauncherMatch_matchesTwitterByAliasWhenUserRequestsX() {
        val match = AppOpener.findBestLauncherMatch(
            launcherApps = listOf(
                AppOpener.LauncherAppMatch(
                    packageName = "com.twitter.android",
                    label = "Twitter"
                )
            ),
            requestedName = "x"
        )

        assertEquals("com.twitter.android", match?.packageName)
    }

    @Test
    fun findBestLauncherMatch_doesNotMatchAliasAgainstRelatedPackageNames() {
        val match = AppOpener.findBestLauncherMatch(
            launcherApps = listOf(
                AppOpener.LauncherAppMatch(
                    packageName = "ai.x.grok",
                    label = "Grok"
                )
            ),
            requestedName = "x"
        )

        assertNull(match)
    }

    @Test
    fun findBestLauncherMatch_keepsTokenLevelMatchesForNormalAppNames() {
        val match = AppOpener.findBestLauncherMatch(
            launcherApps = listOf(
                AppOpener.LauncherAppMatch(
                    packageName = "org.mozilla.firefox",
                    label = "Firefox"
                ),
                AppOpener.LauncherAppMatch(
                    packageName = "com.google.android.apps.maps",
                    label = "Google Maps"
                )
            ),
            requestedName = "maps"
        )

        assertEquals("com.google.android.apps.maps", match?.packageName)
    }
}
