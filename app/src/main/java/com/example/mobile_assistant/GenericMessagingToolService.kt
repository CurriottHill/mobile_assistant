package com.example.mobile_assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import org.json.JSONObject

internal class GenericMessagingToolService(
    private val context: Context
) {
    fun execute(arguments: JSONObject): SharedToolExecutionResult {
        val app = arguments.optString("app").trim().lowercase()
        val target = arguments.optString("target").trim()
        val message = arguments.optString("message").trim()
        val targetKind = arguments.optString("target_kind").trim().ifBlank { null }

        if (app !in SUPPORTED_APPS) {
            return error(
                error = "Unsupported messaging app: $app",
                app = app,
                target = target,
                message = message
            )
        }
        if (target.isBlank()) {
            return error("Missing target.", app, target, message)
        }
        if (message.isBlank()) {
            return error("Missing message.", app, target, message)
        }

        val packageName = packageFor(app)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (resolveActivity(shareIntent) == null) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_SEND_MESSAGE,
                content = baseContent(app, target, message)
                    .put("ok", false)
                    .put("target_kind", targetKind ?: JSONObject.NULL)
                    .put("package", packageName)
                    .put("app_installed", false)
                    .put("error", "$app is not installed or cannot handle shared text."),
                chatResponse = "$app is not available for sending that message."
            )
        }

        return runCatching {
            context.startActivity(shareIntent)
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_SEND_MESSAGE,
                content = baseContent(app, target, message)
                    .put("ok", false)
                    .put("target_kind", targetKind ?: JSONObject.NULL)
                    .put("package", packageName)
                    .put("app_installed", true)
                    .put("opened", true)
                    .put("needs_manual_app", true)
                    .put("error", "Opened $app with the message text, but recipient selection and final send need the phone agent."),
                chatResponse = "I opened $app with the message ready, but I need to finish selecting the recipient on screen."
            )
        }.getOrElse { throwable ->
            error(
                error = throwable.message ?: "Failed to open $app.",
                app = app,
                target = target,
                message = message
            )
        }
    }

    private fun error(error: String, app: String, target: String, message: String): SharedToolExecutionResult {
        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_SEND_MESSAGE,
            content = baseContent(app, target, message)
                .put("ok", false)
                .put("error", error),
            chatResponse = error
        )
    }

    private fun baseContent(app: String, target: String, message: String): JSONObject {
        return JSONObject()
            .put("tool", SharedToolSchemas.TOOL_SEND_MESSAGE)
            .put("app", app)
            .put("target", target)
            .put("message", message)
    }

    private fun packageFor(app: String): String = when (app) {
        "telegram" -> "org.telegram.messenger"
        "signal" -> "org.thoughtcrime.securesms"
        else -> app
    }

    private fun resolveActivity(intent: Intent): ResolveInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    private companion object {
        private val SUPPORTED_APPS = setOf("telegram", "signal")
    }
}
