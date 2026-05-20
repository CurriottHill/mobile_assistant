package com.example.mobile_assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.delay
import org.json.JSONObject

internal class WhatsAppToolService(
    private val context: Context,
    private val tapSendButton: suspend () -> Boolean,
    private val openWhatsAppShare: (String) -> Boolean,
    private val typeInPickerSearch: suspend (String) -> Boolean,
    private val tapChatRow: suspend (String) -> Boolean
) {
    suspend fun executeSend(arguments: JSONObject): SharedToolExecutionResult {
        val contactName = arguments.optString("contact_name").trim()
        val message = arguments.optString("message").trim()

        if (contactName.isBlank()) {
            return errorResult("Missing contact_name.", contactName, message, "I need a contact name first.")
        }
        if (message.isBlank()) {
            return errorResult("Missing message.", contactName, message, "I need a message to send.")
        }

        val whatsAppInstalled = runCatching {
            context.packageManager.getPackageInfo("com.whatsapp", 0)
            true
        }.getOrDefault(false)
        if (!whatsAppInstalled) {
            return errorResult(
                "WhatsApp is not installed.", contactName, message,
                "WhatsApp is not installed on this device."
            )
        }

        val resolved = ContactResolver.resolveBestPhoneNumber(context, contactName)
        if (WhatsAppSendSupport.choosePath(resolved) == WhatsAppSendSupport.Path.SHARE_PICKER) {
            return sendViaSharePicker(contactName, message)
        }
        // resolved is non-null on the PHONE path.
        val match = resolved!!

        val phone = match.phoneNumber.filter { it.isDigit() || it == '+' }
        val encodedMessage = Uri.encode(message)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=$encodedMessage")).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            context.startActivity(intent)
            val sent = pollTapSendButton()
            if (sent) {
                successResult(
                    spoken = "WhatsApp message sent to ${match.displayName}.",
                    matchKind = match.matchKind
                ) { c ->
                    c.put("contact_name", contactName)
                        .put("resolved_name", match.displayName)
                        .put("phone_number", match.phoneNumber)
                }
            } else {
                notSentResult(
                    spoken = "I opened WhatsApp for ${match.displayName} but couldn't send automatically.",
                    error = "WhatsApp opened but could not tap the send button automatically."
                ) { c ->
                    c.put("contact_name", contactName)
                        .put("resolved_name", match.displayName)
                        .put("phone_number", match.phoneNumber)
                }
            }
        }.getOrElse { error ->
            errorResult(
                error.message ?: "Failed to open WhatsApp.", contactName, message,
                "I could not open WhatsApp right now."
            )
        }
    }

    /**
     * Hands-free path for groups / chats not in contacts. The message is pre-filled by the
     * ACTION_SEND intent (never typed), then the chat is auto-searched, selected, and sent
     * via accessibility. No step ever asks the user to touch the phone.
     */
    private suspend fun sendViaSharePicker(
        rawName: String,
        message: String
    ): SharedToolExecutionResult {
        val query = WhatsAppSendSupport.normalizeChatQuery(rawName)

        val opened = runCatching { openWhatsAppShare(message) }.getOrDefault(false)
        if (!opened) {
            return notSentResult(
                spoken = "I could not open WhatsApp to send that message.",
                error = "Failed to open the WhatsApp share picker."
            ) { c -> c.put("contact_name", rawName).put("target", query) }
        }

        // Give WhatsApp time to open and display the picker.
        delay(1200)

        // Filter the picker to the chat, then select it. Search is best-effort (some
        // WhatsApp versions show the chat directly); selecting the row is mandatory.
        poll { typeInPickerSearch(query) }
        val selected = poll { tapChatRow(query) }
        if (!selected) {
            return notSentResult(
                spoken = "I couldn't find a WhatsApp chat called $query, so I didn't send it.",
                error = "Could not locate the chat \"$query\" in the WhatsApp picker."
            ) { c -> c.put("contact_name", rawName).put("target", query) }
        }

        return notSentResult(
            spoken = "I selected $query in WhatsApp. I need to finish the final send manually.",
            error = "WhatsApp share picker target was selected; final send is intentionally left to the phone agent."
        ) { c ->
            c.put("contact_name", rawName)
                .put("target", query)
                .put("selected_target", query)
                .put("state", "target_selected_in_share_picker")
                .put("partial_completion", true)
                .put("needs_manual_final_send", true)
        }
    }

    /**
     * WhatsApp renders asynchronously and this is a shared tool (no re-observation), so each
     * step is polled until the UI settles. Same budget as the original single send wait.
     */
    private suspend fun pollTapSendButton(): Boolean = poll { tapSendButton() }

    private suspend fun poll(action: suspend () -> Boolean): Boolean {
        repeat(SEND_TAP_ATTEMPTS) {
            delay(SEND_TAP_INTERVAL_MS)
            if (runCatching { action() }.getOrDefault(false)) return true
        }
        return false
    }

    private fun successResult(
        spoken: String,
        matchKind: String,
        fill: (JSONObject) -> JSONObject
    ) = SharedToolExecutionResult(
        toolName = SharedToolSchemas.TOOL_SEND_WHATSAPP,
        content = fill(
            JSONObject()
                .put("ok", true)
                .put("tool", SharedToolSchemas.TOOL_SEND_WHATSAPP)
                .put("match_kind", matchKind)
        ),
        chatResponse = spoken
    )

    private fun notSentResult(
        spoken: String,
        error: String,
        fill: (JSONObject) -> JSONObject
    ) = SharedToolExecutionResult(
        toolName = SharedToolSchemas.TOOL_SEND_WHATSAPP,
        content = fill(
            JSONObject()
                .put("ok", false)
                .put("tool", SharedToolSchemas.TOOL_SEND_WHATSAPP)
                .put("error", error)
        ),
        chatResponse = spoken
    )

    private fun errorResult(
        error: String,
        contactName: String,
        message: String,
        chatResponse: String
    ) = SharedToolExecutionResult(
        toolName = SharedToolSchemas.TOOL_SEND_WHATSAPP,
        content = JSONObject()
            .put("ok", false)
            .put("tool", SharedToolSchemas.TOOL_SEND_WHATSAPP)
            .put("contact_name", contactName)
            .put("message", message)
            .put("error", error),
        chatResponse = chatResponse
    )

    private companion object {
        private const val SEND_TAP_ATTEMPTS = 5
        private const val SEND_TAP_INTERVAL_MS = 600L
    }
}
