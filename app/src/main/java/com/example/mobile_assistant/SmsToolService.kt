package com.example.mobile_assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

internal class SmsToolService(
    private val context: Context
) {
    fun executeSendSms(arguments: JSONObject): SharedToolExecutionResult {
        val contactName = arguments.optString("contact_name").trim()
        val message = arguments.optString("message").trim()

        if (contactName.isBlank()) {
            return errorResult("Missing contact_name.", contactName, message, "I need a contact name first.")
        }
        if (message.isBlank()) {
            return errorResult("Missing message.", contactName, message, "I need a message to send.")
        }

        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasSmsPermission) {
            return errorResult(
                "SEND_SMS permission is not granted.", contactName, message,
                "I need SMS permission before I can send messages."
            )
        }

        val resolved = resolveBestPhoneNumber(contactName)
            ?: return errorResult(
                "No matching contact or phone number found.", contactName, message,
                "I could not find a phone number for $contactName."
            )

        return runCatching {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(resolved.phoneNumber, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(resolved.phoneNumber, null, parts, null, null)
            }
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_SEND_SMS,
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", SharedToolSchemas.TOOL_SEND_SMS)
                    .put("contact_name", contactName)
                    .put("resolved_name", resolved.displayName)
                    .put("phone_number", resolved.phoneNumber)
                    .put("match_kind", resolved.matchKind),
                chatResponse = "Message sent to ${resolved.displayName}."
            )
        }.getOrElse { error ->
            errorResult(
                error.message ?: "Failed to send SMS.", contactName, message,
                "I could not send the message right now."
            )
        }
    }

    private fun resolveBestPhoneNumber(input: String): ContactPhoneMatch? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        sanitizePhoneNumber(trimmed)?.let { number ->
            return ContactPhoneMatch(
                displayName = trimmed,
                phoneNumber = number,
                matchKind = "direct_number"
            )
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
        toolName = SharedToolSchemas.TOOL_SEND_SMS,
        content = JSONObject()
            .put("ok", false)
            .put("tool", SharedToolSchemas.TOOL_SEND_SMS)
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
