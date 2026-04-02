package com.example.mobile_assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

internal class CallToolService(
    private val context: Context
) {
    fun executeCall(arguments: JSONObject): SharedToolExecutionResult {
        val contactName = arguments.optString("contact_name").trim()
        if (contactName.isBlank()) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CALL_CONTACT,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_CALL_CONTACT)
                    .put("error", "Missing contact_name."),
                chatResponse = "I need a contact name first."
            )
        }

        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CALL_CONTACT,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_CALL_CONTACT)
                    .put("contact_name", contactName)
                    .put("error", "CALL_PHONE permission is not granted."),
                chatResponse = "I need phone call permission before I can place calls."
            )
        }

        val resolved = resolveBestPhoneNumber(contactName)
            ?: return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CALL_CONTACT,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_CALL_CONTACT)
                    .put("contact_name", contactName)
                    .put("error", "No matching contact or phone number found."),
                chatResponse = "I could not find a phone number for $contactName."
            )

        val dialUri = Uri.parse("tel:${Uri.encode(resolved.phoneNumber)}")
        val callIntent = Intent(Intent.ACTION_CALL, dialUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            context.startActivity(callIntent)
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CALL_CONTACT,
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", SharedToolSchemas.TOOL_CALL_CONTACT)
                    .put("contact_name", contactName)
                    .put("resolved_name", resolved.displayName)
                    .put("phone_number", resolved.phoneNumber)
                    .put("match_kind", resolved.matchKind),
                chatResponse = "Calling ${resolved.displayName} now."
            )
        }.getOrElse { error ->
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CALL_CONTACT,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_CALL_CONTACT)
                    .put("contact_name", contactName)
                    .put("resolved_name", resolved.displayName)
                    .put("phone_number", resolved.phoneNumber)
                    .put("error", error.message ?: "Failed to start call."),
                chatResponse = "I could not start the call right now."
            )
        }
    }

    private fun resolveBestPhoneNumber(input: String): ContactPhoneMatch? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        // If the user already gave a phone number, use it directly.
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
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!canReadContacts) return null

        val matches = mutableListOf<ContactPhoneMatch>()
        val contentResolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        runCatching {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
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
                    val score = matchScore(normalizedQuery, normalizedName)
                    if (score == null) continue

                    matches += ContactPhoneMatch(
                        displayName = displayName,
                        phoneNumber = sanitizedNumber,
                        matchKind = score.matchKind,
                        score = score.value
                    )
                }
            }
        }.getOrNull()

        return matches
            .minWithOrNull(
                compareBy<ContactPhoneMatch> { it.score ?: Int.MAX_VALUE }
                    .thenBy { it.displayName.length }
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

    private fun normalizeName(value: String): String {
        return value
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun matchScore(query: String, name: String): NameMatchScore? {
        if (name == query) return NameMatchScore(0, "exact")
        val nameTokens = name.split(" ").filter { it.isNotBlank() }
        if (nameTokens.any { it == query }) return NameMatchScore(1, "token_exact")
        if (name.startsWith("$query ")) return NameMatchScore(2, "prefix")
        if (name.contains(" $query ")) return NameMatchScore(3, "contains")
        if (nameTokens.any { token -> token.startsWith(query) && query.length >= 2 }) {
            return NameMatchScore(4, "token_prefix")
        }
        return null
    }

    private data class NameMatchScore(
        val value: Int,
        val matchKind: String
    )

    private data class ContactPhoneMatch(
        val displayName: String,
        val phoneNumber: String,
        val matchKind: String,
        val score: Int? = null
    )
}
