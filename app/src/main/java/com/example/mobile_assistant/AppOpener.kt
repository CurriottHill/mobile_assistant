package com.example.mobile_assistant

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import java.util.Locale

internal object AppOpener {
    private const val NO_MATCH_SCORE = Int.MAX_VALUE
    private val APP_NAME_ALIASES = mapOf(
        "x" to setOf("x", "twitter"),
        "twitter" to setOf("twitter", "x")
    )

    fun openApp(service: AssistantAccessibilityService, requestedName: String): AppOpenResult {
        val normalizedName = requestedName.trim().lowercase(Locale.US)
        if (normalizedName.isBlank()) {
            return AppOpenResult(opened = false, error = "Missing app name.")
        }

        val packageManager = service.packageManager
        val match = findBestLauncherMatch(packageManager, normalizedName)
            ?: return AppOpenResult(
                opened = false,
                error = "No installed launcher app matched '$normalizedName'."
            )

        if (currentForegroundPackage(service) == match.packageName) {
            return AppOpenResult(
                opened = true,
                packageName = match.packageName,
                label = match.label,
                alreadyOpen = true
            )
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(match.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } ?: return AppOpenResult(
            opened = false,
            packageName = match.packageName,
            label = match.label,
            error = "Resolved '${match.label}' but it has no launchable activity."
        )

        return runCatching {
            service.startActivity(launchIntent)
            AppOpenResult(
                opened = true,
                packageName = match.packageName,
                label = match.label
            )
        }.getOrElse { error ->
            AppOpenResult(
                opened = false,
                packageName = match.packageName,
                label = match.label,
                error = error.message ?: "Failed to launch app."
            )
        }
    }

    fun openUrl(service: AssistantAccessibilityService, requestedUrl: String): UrlOpenResult {
        val normalizedUrl = normalizeUrl(requestedUrl)
            ?: return UrlOpenResult(opened = false, error = "Missing or invalid URL.")

        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        val resolveInfo = resolveBrowserActivity(service.packageManager, browserIntent)
            ?: return UrlOpenResult(
                opened = false,
                requestedUrl = normalizedUrl,
                error = "No browser is available to open '$normalizedUrl'."
            )

        val activityInfo = resolveInfo.activityInfo
        val packageName = activityInfo?.packageName
        val label = resolveInfo.loadLabel(service.packageManager)?.toString()?.trim().orEmpty()
        val targetAlreadyForeground = packageName != null && currentForegroundPackage(service) == packageName

        return runCatching {
            service.startActivity(browserIntent)
            UrlOpenResult(
                opened = true,
                requestedUrl = normalizedUrl,
                packageName = packageName,
                label = label.ifBlank { null },
                targetAlreadyForeground = targetAlreadyForeground
            )
        }.getOrElse { error ->
            UrlOpenResult(
                opened = false,
                requestedUrl = normalizedUrl,
                packageName = packageName,
                label = label.ifBlank { null },
                error = error.message ?: "Failed to open URL."
            )
        }
    }

    private fun findBestLauncherMatch(
        packageManager: PackageManager,
        requestedName: String
    ): LauncherAppMatch? {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherApps = queryLauncherActivities(packageManager, launcherIntent)
            .mapNotNull { resolveInfo -> resolveLauncherApp(packageManager, resolveInfo) }
        return findBestLauncherMatch(launcherApps, requestedName)
    }

    internal fun findBestLauncherMatch(
        launcherApps: List<LauncherAppMatch>,
        requestedName: String
    ): LauncherAppMatch? {
        val normalizedRequestedName = normalizeLookupValue(requestedName)
        if (normalizedRequestedName.isBlank()) return null

        return launcherApps
            .distinctBy { it.packageName }
            .minWithOrNull(
                compareBy<LauncherAppMatch> { matchScore(normalizedRequestedName, it) }
                    .thenBy { it.label.length }
                    .thenBy { it.packageName.length }
            )
            ?.takeIf { matchScore(normalizedRequestedName, it) < NO_MATCH_SCORE }
    }

    private fun queryLauncherActivities(
        packageManager: PackageManager,
        launcherIntent: Intent
    ): List<ResolveInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }
    }

    private fun resolveLauncherApp(
        packageManager: PackageManager,
        resolveInfo: ResolveInfo
    ): LauncherAppMatch? {
        val activityInfo = resolveInfo.activityInfo ?: return null
        val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
        if (label.isBlank()) return null
        return LauncherAppMatch(
            packageName = activityInfo.packageName,
            label = label
        )
    }

    private fun resolveBrowserActivity(
        packageManager: PackageManager,
        browserIntent: Intent
    ): ResolveInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                browserIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    private fun matchScore(requestedName: String, candidate: LauncherAppMatch): Int {
        val label = normalizeLookupValue(candidate.label)
        val packageName = candidate.packageName.lowercase(Locale.US)
        val labelTokens = tokenizeLookupValue(label)
        val packageTokens = tokenizeLookupValue(packageName)

        val directMatchScore = when {
            label == requestedName -> 0
            labelTokens.contains(requestedName) -> 1
            packageName == requestedName || packageName.endsWith(".$requestedName") -> 2
            requestedName.length >= 3 && packageTokens.contains(requestedName) -> 3
            requestedName.length >= 3 && labelTokens.any { token -> token.startsWith(requestedName) } -> 4
            requestedName.length >= 4 && packageTokens.any { token -> token.startsWith(requestedName) } -> 5
            else -> NO_MATCH_SCORE
        }

        val aliasMatchScore = requestedNameVariants(requestedName)
            .asSequence()
            .filter { variant -> variant != requestedName }
            .minOfOrNull { variant ->
                when {
                    label == variant -> 6
                    labelTokens.contains(variant) -> 7
                    variant.length >= 3 && labelTokens.any { token -> token.startsWith(variant) } -> 8
                    else -> NO_MATCH_SCORE
                }
            }
            ?: NO_MATCH_SCORE

        return minOf(directMatchScore, aliasMatchScore)
    }

    private fun requestedNameVariants(requestedName: String): Set<String> {
        return buildSet {
            add(requestedName)
            APP_NAME_ALIASES[requestedName]
                ?.mapTo(this) { alias -> normalizeLookupValue(alias) }
        }
    }

    private fun normalizeLookupValue(value: String): String {
        return value
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun tokenizeLookupValue(value: String): Set<String> {
        return value
            .split(Regex("[^a-z0-9]+"))
            .map { token -> token.trim() }
            .filter { token -> token.isNotEmpty() }
            .toSet()
    }

    internal data class LauncherAppMatch(
        val packageName: String,
        val label: String
    )

    private fun currentForegroundPackage(service: AssistantAccessibilityService): String? {
        return AssistantUiTargeting.currentUnderlyingPackage(service)
    }

    private fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null

        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = Uri.parse(withScheme)
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host?.trim().orEmpty()

        return withScheme.takeIf {
            scheme in setOf("http", "https") && host.isNotBlank()
        }
    }
}
