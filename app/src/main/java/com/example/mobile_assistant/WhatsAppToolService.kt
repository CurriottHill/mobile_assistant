package com.example.mobile_assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

internal class WhatsAppToolService(
    private val context: Context,
    private val tapSendButton: suspend () -> Boolean
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

        val resolved = resolveBestPhoneNumber(contactName)
            ?: return errorResult(
                "No matching contact or phone number found.", contactName, message,
                "I could not find a phone number for $contactName."
            )

        val phone = resolved.phoneNumber.filter { it.isDigit() || it == '+' }
        val encodedMessage = Uri.encode(message)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=$encodedMessage")).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            context.startActivity(intent)
            delay(2000)
            val sent = tapSendButton()
            if (sent) {
                SharedToolExecutionResult(
                    toolName = SharedToolSchemas.TOOL_SEND_WHATSAPP,
                    content = JSONObject()
                        .put("ok", true)
                        .put("tool", SharedToolSchemas.TOOL_SEND_WHATSAPP)
                        .put("contact_name", contactName)
                        .put("resolved_name", resolved.displayName)
                        .put("phone_number", resolved.phoneNumber)
                        .put("match_kind", resolved.matchKind),
                    chatResponse = "WhatsApp message sent to ${resolved.displayName}."
                )
            } else {
                SharedToolExecutionResult(
                    toolName = SharedToolSchemas.TOOL_SEND_WHATSAPP,
                    content = JSONObject()
                        .put("ok", false)
                        .put("tool", SharedToolSchemas.TOOL_SEND_WHATSAPP)
                        .put("contact_name", contactName)
                        .put("resolved_name", resolved.displayName)
                        .put("phone_number", resolved.phoneNumber)
                        .put("error", "WhatsApp opened but could not tap the send button automatically."),
                    chatResponse = "I opened WhatsApp for ${resolved.displayName} but couldn't tap send automatically. Please tap send."
                )
            }
        }.getOrElse { error ->
            errorResult(
                error.message ?: "Failed to open WhatsApp.", contactName, message,
                "I could not open WhatsApp right now."
            )
        }
    }

    private fun resolveBestPhoneNumber(input: String): ContactPhoneMatch? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        sanitizePhoneNumber(trimmed)?.let { number ->
            return ContactPhoneMatch(displayName = trimmed, phoneNumber = number, matchKind = "direct_number")
        }

        val normalizedQuery = normalizeName(trimmed)
        if (normalizedQuery.isBlank()) return null

        val canReadContacts = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!canReadContacts) return null

        val matches = mutableListOf<ContactPhoneMatch>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIndex < 0 || numberIndex < 0) return@use

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex)?.trim().orEmpty()
                    val numberRaw = cursor.getString(numberIndex)?.trim().orEmpty()
                    if (displayName.isBlank() || numberRaw.isBlank()) continue
                    val sanitizedNumber = sanitizePhoneNumber(numberRaw) ?: continue
                    val normalizedName = normalizeName(displayName)
                    if (normalizedName.isBlank()) continue
                    val score = matchScore(normalizedQuery, normalizedName) ?: continue
                    matches += ContactPhoneMatch(
                        displayName = displayName,
                        phoneNumber = sanitizedNumber,
                        matchKind = score.matchKind,
                        score = score.value
                    )
                }
            }
        }

        return matches.minWithOrNull(
            compareBy<ContactPhoneMatch> { it.score ?: Int.MAX_VALUE }.thenBy { it.displayName.length }
        )
    }

    private fun sanitizePhoneNumber(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val hasPlus = trimmed.startsWith("+")
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.length < 3) return null
        return if (hasPlus) "+$digitsOnly" else digitsOnly
    }

    private fun normalizeName(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun matchScore(query: String, name: String): NameMatchScore? {
        if (name == query) return NameMatchScore(0, "exact")
        val nameTokens = name.split(" ").filter { it.isNotBlank() }
        if (nameTokens.any { it == query }) return NameMatchScore(1, "token_exact")
        if (name.startsWith("$query ")) return NameMatchScore(2, "prefix")
        if (name.contains(" $query ")) return NameMatchScore(3, "contains")
        if (nameTokens.any { token -> token.startsWith(query) && query.length >= 2 }) return NameMatchScore(4, "token_prefix")
        return null
    }

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

    private data class NameMatchScore(val value: Int, val matchKind: String)

    private data class ContactPhoneMatch(
        val displayName: String,
        val phoneNumber: String,
        val matchKind: String,
        val score: Int? = null
    )
}
