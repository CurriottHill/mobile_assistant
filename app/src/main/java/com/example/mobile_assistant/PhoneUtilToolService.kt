package com.example.mobile_assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small phone utilities that need no special permission: enumerate launchable apps and
 * read/write the system clipboard.
 */
internal class PhoneUtilToolService(private val context: Context) {

    fun executeListApps(arguments: JSONObject): SharedToolExecutionResult {
        val filter = arguments.optString("filter").trim().lowercase().ifBlank { null }
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }.getOrDefault(emptyList())

        val apps = resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName?.trim().orEmpty()
                if (pkg.isBlank()) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                label to pkg
            }
            .distinctBy { it.second }
            .filter { (label, pkg) ->
                filter == null ||
                    label.lowercase().contains(filter) ||
                    pkg.lowercase().contains(filter)
            }
            .sortedBy { it.first.lowercase() }

        val content = JSONObject()
            .put("ok", true)
            .put("tool", SharedToolSchemas.TOOL_LIST_APPS)
            .put("count", apps.size)
            .also { c -> filter?.let { c.put("filter", it) } }
            .put("apps", JSONArray().also { arr ->
                apps.forEach { (label, pkg) ->
                    arr.put(JSONObject().put("label", label).put("package", pkg))
                }
            })

        val chatResponse = when {
            apps.isEmpty() && filter != null -> "I did not find any installed apps matching $filter."
            apps.isEmpty() -> "I did not find any launchable apps."
            else -> "Found ${apps.size} app${if (apps.size == 1) "" else "s"}: " +
                apps.take(8).joinToString(", ") { it.first } +
                if (apps.size > 8) ", and more." else "."
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_LIST_APPS,
            content = content,
            chatResponse = chatResponse
        )
    }

    fun executeClipboardSet(arguments: JSONObject): SharedToolExecutionResult {
        val text = arguments.optString("text")
        if (text.isEmpty()) {
            return clipboardError(
                SharedToolSchemas.TOOL_CLIPBOARD_SET,
                "Missing text.",
                "I need some text to copy to the clipboard."
            )
        }
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("assistant", text))
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CLIPBOARD_SET,
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", SharedToolSchemas.TOOL_CLIPBOARD_SET)
                    .put("length", text.length),
                chatResponse = "Copied that to the clipboard."
            )
        }.getOrElse { error ->
            clipboardError(
                SharedToolSchemas.TOOL_CLIPBOARD_SET,
                error.message ?: "Failed to set clipboard.",
                "I could not write to the clipboard just now."
            )
        }
    }

    fun executeClipboardGet(): SharedToolExecutionResult {
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
            if (text.isNullOrEmpty()) {
                clipboardError(
                    SharedToolSchemas.TOOL_CLIPBOARD_GET,
                    "clipboard empty or not accessible",
                    "The clipboard is empty or not accessible right now."
                )
            } else {
                SharedToolExecutionResult(
                    toolName = SharedToolSchemas.TOOL_CLIPBOARD_GET,
                    content = JSONObject()
                        .put("ok", true)
                        .put("tool", SharedToolSchemas.TOOL_CLIPBOARD_GET)
                        .put("text", text),
                    chatResponse = "The clipboard contains: $text"
                )
            }
        }.getOrElse { error ->
            clipboardError(
                SharedToolSchemas.TOOL_CLIPBOARD_GET,
                error.message ?: "clipboard empty or not accessible",
                "I could not read the clipboard just now."
            )
        }
    }

    private fun clipboardError(tool: String, error: String, chatResponse: String) =
        SharedToolExecutionResult(
            toolName = tool,
            content = JSONObject()
                .put("ok", false)
                .put("tool", tool)
                .put("error", error),
            chatResponse = chatResponse
        )
}
